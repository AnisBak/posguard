package dev.posguard.ebt

import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.event.GuardEvent
import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveryOutcome
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TxnState
import dev.posguard.ebt.EbtEntryMode.CHIP_INSERT
import dev.posguard.ebt.EbtEntryMode.CHIP_TAP
import dev.posguard.ebt.EbtEntryMode.MAGSTRIPE
import dev.posguard.ledger.PosGuardLedger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted fake engine. Not real: no card data, no PIN. */
private class FakeEbtEngine : EbtEngine {
    val reads = ArrayDeque<CardRead.Failed>()
    var purchaseResult: (EbtPurchaseRequest) -> EbtPurchaseResult =
        { EbtPurchaseResult.Approved("ref-1", it.amount, EbtBalance("USD", snapCents = 1234, cashCents = null)) }
    val purchases = CopyOnWriteArrayList<EbtPurchaseRequest>()
    val statuses = mutableMapOf<IdempotencyKey, EngineStatus>()
    var throwOnRead = false

    override suspend fun readCard(mode: EbtEntryMode): CardRead {
        if (throwOnRead) {
            throwOnRead = false
            error("reader glitch")
        }
        return reads.removeFirstOrNull() ?: CardRead.Ready("h-$mode", mode)
    }

    override suspend fun purchase(request: EbtPurchaseRequest): EbtPurchaseResult {
        purchases += request
        return purchaseResult(request)
    }

    override suspend fun queryStatus(key: IdempotencyKey): EngineStatus = statuses[key] ?: EngineStatus.Unreachable
}

@RunWith(RobolectricTestRunner::class)
class EbtCheckoutTest {

    private val engine = FakeEbtEngine()
    private val events = CopyOnWriteArrayList<GuardEvent>()
    private val ledger: TransactionLedger =
        PosGuardLedger.Builder(ApplicationProvider.getApplicationContext(), engine).inMemory().build()
    private val config = EbtConfig(EligibilityRules(mapOf("produce" to Eligibility.ELIGIBLE)))
    private val checkout = EbtCheckout(ledger, engine, config, listOf(GuardEventListener { events += it }))
    private val snapLeg = TenderLeg(Tender.EBT_SNAP, Money(1000, "USD"))

    @Test
    fun `approved leg is recorded once and returns the balance for the receipt`() = runTest {
        val prompts = mutableListOf<EntryPrompt.Present>()
        val result = checkout.payLeg(snapLeg) { prompts += it } as EbtLegResult.Approved

        assertEquals(listOf(EntryPrompt.Present(CHIP_INSERT)), prompts)
        assertFalse(result.isPartial)
        assertEquals(CHIP_INSERT, result.entryMode)
        assertEquals(1234L, result.balance!!.snapCents)
        val record = ledger.get(result.txnId)!!
        assertEquals(TxnState.AUTHORIZED, record.state)
        assertEquals(Tender.EBT_SNAP, record.tender)
        assertNull(record.approvedAmount)
        assertEquals(1, engine.purchases.size)
        assertEquals(record.idempotencyKey, engine.purchases.single().idempotencyKey)
        assertTrue(events.none { it is GuardEvent.EbtEntryFallback || it is GuardEvent.EbtPartialApproval })
    }

    @Test
    fun `chip failure falls back to tap and emits a fallback event`() = runTest {
        engine.reads += CardRead.Failed(EntryFailure.CHIP_READ_FAILED)
        val prompts = mutableListOf<EntryPrompt.Present>()
        val result = checkout.payLeg(snapLeg) { prompts += it } as EbtLegResult.Approved

        assertEquals(listOf(CHIP_INSERT, CHIP_TAP), prompts.map { it.mode })
        assertEquals(CHIP_TAP, result.entryMode)
        val fallback = events.filterIsInstance<GuardEvent.EbtEntryFallback>().single()
        assertEquals(listOf("CHIP_INSERT", "CHIP_TAP", "CHIP_READ_FAILED"), listOf(fallback.from, fallback.to, fallback.reason))
        assertEquals(result.txnId, fallback.id)
    }

    @Test
    fun `an engine exception while reading counts as a failed read`() = runTest {
        engine.throwOnRead = true
        val result = checkout.payLeg(snapLeg) as EbtLegResult.Approved
        assertEquals(CHIP_TAP, result.entryMode)
        assertEquals("READ_ERROR", events.filterIsInstance<GuardEvent.EbtEntryFallback>().single().reason)
    }

    @Test
    fun `unreadable card voids the leg and never reaches the processor`() = runTest {
        engine.reads += CardRead.Failed(EntryFailure.CHIP_READ_FAILED)
        engine.reads += CardRead.Failed(EntryFailure.CHIP_READ_FAILED)
        engine.reads += CardRead.Failed(EntryFailure.MAGSTRIPE_READ_FAILED)

        val result = checkout.payLeg(snapLeg) as EbtLegResult.CardUnreadable

        assertEquals(listOf(CHIP_INSERT, CHIP_TAP, MAGSTRIPE), result.attempts.map { it.mode })
        assertEquals(TxnState.VOIDED, ledger.get(result.txnId)!!.state)
        assertTrue(engine.purchases.isEmpty())
        assertNull(events.filterIsInstance<GuardEvent.EbtEntryFallback>().last().to)
    }

    @Test
    fun `partial approval is recorded in the ledger and emits an event without amounts`() = runTest {
        engine.purchaseResult = { EbtPurchaseResult.Approved("ref-p", Money(600, "USD"), EbtBalance("USD", 0, null)) }
        val result = checkout.payLeg(snapLeg) as EbtLegResult.Approved

        assertTrue(result.isPartial)
        assertEquals(Money(600, "USD"), ledger.get(result.txnId)!!.approvedAmount)
        assertEquals(result.txnId, events.filterIsInstance<GuardEvent.EbtPartialApproval>().single().id)
        assertEquals(
            listOf(TenderLeg(Tender.CARD, Money(400, "USD"))),
            EbtTenderPlanner.afterSnapApproval(snapLeg, result.approved, emptyList()),
        )
    }

    @Test
    fun `declined leg is recorded as declined`() = runTest {
        engine.purchaseResult = { EbtPurchaseResult.Declined("ref-d", "51") }
        val result = checkout.payLeg(snapLeg) as EbtLegResult.Declined
        assertEquals("51", result.declineCode)
        assertEquals(TxnState.DECLINED, ledger.get(result.txnId)!!.state)
    }

    @Test
    fun `lost response becomes UNKNOWN and recovery resolves it with the same key`() = runTest {
        engine.purchaseResult = { error("connection dropped") }
        val result = checkout.payLeg(snapLeg) as EbtLegResult.Unknown
        val record = ledger.get(result.txnId)!!
        assertEquals(TxnState.UNKNOWN, record.state)

        engine.statuses[record.idempotencyKey] = EngineStatus.Authorized("ref-late", Money(700, "USD"))
        val recovered = ledger.recoverPending().single()

        assertEquals(RecoveryOutcome.RESOLVED, recovered.outcome)
        assertEquals(TxnState.AUTHORIZED, recovered.record.state)
        assertEquals(Money(700, "USD"), recovered.record.approvedAmount)
        assertEquals(1, engine.purchases.size)
    }

    @Test
    fun `impossible approval is not trusted`() = runTest {
        engine.purchaseResult = { EbtPurchaseResult.Approved("ref-x", Money(5000, "USD")) }
        val result = checkout.payLeg(snapLeg)
        assertTrue(result is EbtLegResult.Unknown)
        assertEquals(TxnState.UNKNOWN, ledger.get(result.txnId)!!.state)
    }

    @Test
    fun `card legs are refused`() = runTest {
        val thrown = runCatching { checkout.payLeg(TenderLeg(Tender.CARD, Money(100, "USD"))) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
