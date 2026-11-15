package dev.posguard.ebtsim

import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TxnState
import dev.posguard.ebt.BasketLine
import dev.posguard.ebt.CardRead
import dev.posguard.ebt.EbtCheckout
import dev.posguard.ebt.EbtConfig
import dev.posguard.ebt.EbtEntryMode
import dev.posguard.ebt.EbtLegResult
import dev.posguard.ebt.EbtPurchaseRequest
import dev.posguard.ebt.EbtPurchaseResult
import dev.posguard.ebt.EbtTenderPlanner
import dev.posguard.ebt.Eligibility
import dev.posguard.ebt.EligibilityRules
import dev.posguard.ebt.EntryFailure
import dev.posguard.ledger.PosGuardLedger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EbtSimulatorTest {

    private fun TestScope.sim(card: SimEbtCard = EbtSimulator.Cards.CHIP_CONTACTLESS) =
        EbtSimulator.Builder().dispatcher(StandardTestDispatcher(testScheduler)).card(card).build()

    private fun req(amount: Long, read: CardRead.Ready, key: String = "k1", tender: Tender = Tender.EBT_SNAP) =
        EbtPurchaseRequest(Money(amount, "USD"), tender, IdempotencyKey(key), read)

    @Test
    fun `card type decides which modes read`() = runTest {
        val magOnly = sim(EbtSimulator.Cards.MAGSTRIPE_ONLY)
        assertEquals(CardRead.Failed(EntryFailure.CARD_HAS_NO_CHIP), magOnly.readCard(EbtEntryMode.CHIP_INSERT))
        assertTrue(magOnly.readCard(EbtEntryMode.MAGSTRIPE) is CardRead.Ready)

        val chipOnly = sim(EbtSimulator.Cards.CHIP_ONLY)
        assertEquals(CardRead.Failed(EntryFailure.MODE_NOT_SUPPORTED), chipOnly.readCard(EbtEntryMode.CHIP_TAP))
        assertTrue(chipOnly.readCard(EbtEntryMode.CHIP_INSERT) is CardRead.Ready)
    }

    @Test
    fun `chip fails once, and an unrecognized chip app keeps failing until reset`() = runTest {
        val s = sim()
        s.setNextScenario(EbtSimScenario.CHIP_FAILS_ONCE)
        assertEquals(CardRead.Failed(EntryFailure.CHIP_READ_FAILED), s.readCard(EbtEntryMode.CHIP_INSERT))
        assertTrue(s.readCard(EbtEntryMode.CHIP_TAP) is CardRead.Ready)

        s.setNextScenario(EbtSimScenario.CHIP_APP_NOT_RECOGNIZED)
        repeat(2) { assertEquals(CardRead.Failed(EntryFailure.CHIP_APP_NOT_RECOGNIZED), s.readCard(EbtEntryMode.CHIP_TAP)) }
        assertTrue(s.readCard(EbtEntryMode.MAGSTRIPE) is CardRead.Ready)
        s.reset()
        assertTrue(s.readCard(EbtEntryMode.CHIP_TAP) is CardRead.Ready)
    }

    @Test
    fun `approves up to the balance, then declines, and deduplicates by key`() = runTest {
        val s = sim(SimEbtCard("test", SimCardType.CHIP_CONTACTLESS, snapCents = 700))
        val read = s.readCard(EbtEntryMode.CHIP_TAP) as CardRead.Ready

        val partial = s.purchase(req(1000, read)) as EbtPurchaseResult.Approved
        assertEquals(Money(700, "USD"), partial.approvedAmount)
        assertEquals(0L, partial.balance!!.snapCents)

        // Same key: the original answer, no second deduction.
        assertEquals(partial.engineReference, (s.purchase(req(1000, read)) as EbtPurchaseResult.Approved).engineReference)
        assertEquals(1, s.processed().size)
        assertEquals(EngineStatus.Authorized(partial.engineReference, Money(700, "USD")), s.queryStatus(IdempotencyKey("k1")))

        val empty = s.purchase(req(100, read, key = "k2")) as EbtPurchaseResult.Declined
        assertEquals(EbtSimulator.DECLINE_INSUFFICIENT_FUNDS, empty.declineCode)
    }

    @Test
    fun `SNAP and cash balances are separate`() = runTest {
        val s = sim(SimEbtCard("test", SimCardType.CHIP_CONTACTLESS, snapCents = 0, cashCents = 500))
        val read = s.readCard(EbtEntryMode.CHIP_INSERT) as CardRead.Ready
        assertTrue(s.purchase(req(300, read, "k1", Tender.EBT_SNAP)) is EbtPurchaseResult.Declined)
        val cash = s.purchase(req(300, read, "k2", Tender.EBT_CASH)) as EbtPurchaseResult.Approved
        assertEquals(200L, cash.balance!!.cashCents)
    }

    @Test
    fun `network drop after submit processes once and status finds it after reconnect`() = runTest {
        val s = sim()
        val read = s.readCard(EbtEntryMode.CHIP_INSERT) as CardRead.Ready
        s.setNextScenario(EbtSimScenario.NETWORK_DROP_AFTER_SUBMIT)

        assertEquals(EbtPurchaseResult.Unknown, s.purchase(req(1000, read)))
        assertEquals(EngineStatus.Unreachable, s.queryStatus(IdempotencyKey("k1")))
        s.reconnect()
        assertTrue(s.queryStatus(IdempotencyKey("k1")) is EngineStatus.Authorized)
        assertEquals(1, s.processed().size)

        // Offline before submit: nothing processed.
        s.disconnect()
        assertEquals(EbtPurchaseResult.Unknown, s.purchase(req(100, read, "k2")))
        s.reconnect()
        assertEquals(EngineStatus.NotProcessed, s.queryStatus(IdempotencyKey("k2")))
    }

    @Test
    fun `end to end - chip fails, tap works, SNAP partially approved, shortfall planned for card`() = runTest {
        val s = sim(SimEbtCard("test", SimCardType.CHIP_CONTACTLESS, snapCents = 500))
        s.setNextScenario(EbtSimScenario.CHIP_FAILS_ONCE)
        val ledger = PosGuardLedger.Builder(ApplicationProvider.getApplicationContext(), s).inMemory().build()
        val config = EbtConfig(EligibilityRules(mapOf("produce" to Eligibility.ELIGIBLE, "household" to Eligibility.INELIGIBLE)))
        val checkout = EbtCheckout(ledger, s, config)

        val split = checkout.split(
            listOf(
                BasketLine("bananas", "produce", Money(199, "USD"), 4),
                BasketLine("soap", "household", Money(425, "USD"), 1),
            ),
        )
        val legs = EbtTenderPlanner.plan(split)
        val snap = checkout.payLeg(legs[0]) as EbtLegResult.Approved

        assertEquals(EbtEntryMode.CHIP_TAP, snap.entryMode)
        assertTrue(snap.isPartial)
        assertEquals(Money(500, "USD"), ledger.get(snap.txnId)!!.approvedAmount)
        assertEquals(TxnState.AUTHORIZED, ledger.get(snap.txnId)!!.state)
        assertEquals(
            Money(425 + 296, "USD"),
            EbtTenderPlanner.afterSnapApproval(legs[0], snap.approved, legs.drop(1)).single().amount,
        )
    }
}
