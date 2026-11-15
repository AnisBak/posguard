package dev.posguard.demo.checkout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TxnOutcome
import dev.posguard.core.ledger.combinedStatusSource
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.core.verdict.ReasonCode
import dev.posguard.demo.DemoThreats
import dev.posguard.demo.di.DemoModule
import dev.posguard.ebt.EbtCheckout
import dev.posguard.ebt.EbtConfig
import dev.posguard.ebt.EbtReceipt
import dev.posguard.ebtsim.EbtSimScenario
import dev.posguard.ebtsim.EbtSimulator
import dev.posguard.integrity.play.FakeIntegrityVerdictSource
import dev.posguard.integrity.play.PlayIntegritySignals
import dev.posguard.integrity.relay.RelayRiskConfig
import dev.posguard.integrity.relay.RelayRiskMonitor
import dev.posguard.integrity.relay.WalletBurstRule
import dev.posguard.ledger.PosGuardLedger
import dev.posguard.mockengine.MockPaymentEngine
import dev.posguard.mockengine.SharedPreferencesMockProcessorStore
import dev.posguard.telemetry.DeviceContext
import dev.posguard.telemetry.EventType
import dev.posguard.telemetry.InMemorySink
import dev.posguard.telemetry.PosGuardTelemetry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

/**
 * Demo scenarios 1–5 (and extra failure modes) through the real controller,
 * ledger, mock engine, demo policy asset, and PosGuard. Every payment test
 * checks the processor charged at most once.
 */
@RunWith(RobolectricTestRunner::class)
class CheckoutControllerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "demo-test-${System.nanoTime()}.db"
    private val prefsName = "demo-test-processor-${System.nanoTime()}"

    private var killed = 0
    private var recoveryScheduled = 0

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
        context.deleteSharedPreferences(prefsName)
    }

    /** One "app process": fresh in-memory objects over the same persisted journal and processor. */
    private inner class App(scope: TestScope) {
        val engine: MockPaymentEngine = MockPaymentEngine.Builder()
            .store(SharedPreferencesMockProcessorStore(context, prefsName))
            .build()
        val sink = InMemorySink()
        val telemetry = PosGuardTelemetry.Builder(DeviceContext("Acme", "Test", "15", 35, "0.1.0"))
            .addSink(sink).batchSize(1).scope(scope.backgroundScope).build()
        val ebtSim: EbtSimulator = EbtSimulator.Builder().build()
        val ledger: TransactionLedger =
            PosGuardLedger.Builder(context, combinedStatusSource(engine, ebtSim)).databaseName(dbName).build()
        val ebt = EbtCheckout(
            ledger,
            ebtSim,
            context.assets.open(DemoModule.DEMO_EBT_ASSET).use { EbtConfig.fromProperties(Properties().apply { load(it) }) },
            listeners = listOf(telemetry),
        )
        val threats = DemoThreats()
        val integrityServer = FakeIntegrityVerdictSource()
        val policy: GuardPolicy = context.assets.open(DemoModule.DEMO_POLICY_ASSET)
            .use { GuardPolicy.fromProperties(Properties().apply { load(it) }) }
        val relay = RelayRiskMonitor(
            context.assets.open(DemoModule.DEMO_RELAY_ASSET)
                .use { RelayRiskConfig.fromProperties(Properties().apply { load(it) }) },
        )
        val guard: PosGuard = PosGuard.Builder(ledger)
            .signals(PlayIntegritySignals.create(integrityServer, ttl = 1.seconds))
            .signals(threats.signals)
            .signals(relay.signal)
            .policy(policy)
            .build()
        val resetStore = object : ResetStore { override var resetAtMillis = 0L }
        val controller = CheckoutController(
            scope = scope.backgroundScope,
            guard = guard,
            ledger = ledger,
            engine = engine,
            threats = threats,
            integrityServer = integrityServer,
            telemetry = telemetry,
            events = sink,
            relay = relay,
            ebt = ebt,
            ebtSim = ebtSim,
            resetStore = resetStore,
            scheduleRecovery = { recoveryScheduled++ },
            processKiller = { killed++ },
        )

        suspend fun charges() = engine.processedCharges().size
        suspend fun history() = controller.history.first()
        val ui get() = controller.ui.value
        val phase get() = (ui.screen as Screen.Payment).phase
    }

    private suspend fun App.startWithCart(): App = apply {
        controller.start()
        controller.add("coffee")
        controller.add("bagel")
    }

    @Test
    fun `demo policy asset allows noted reasons`() = runTest {
        val app = App(this)
        assertEquals(dev.posguard.core.verdict.GuardAction.ALLOW, app.policy.actionFor(ReasonCode.INTEGRITY_UNAVAILABLE))
        assertEquals(dev.posguard.core.verdict.GuardAction.BLOCK, app.policy.actionFor(ReasonCode.SCREEN_OVERLAY_PRESENT))
    }

    @Test
    fun `scenario 1 - normal payment is allowed and completed with one charge`() = runTest {
        val app = App(this).startWithCart()
        assertEquals(575L, app.ui.totalCents)

        app.controller.takePayment()
        assertTrue(app.ui.screen is Screen.Payment)
        assertTrue((app.ui.screen as Screen.Payment).verdict is GuardVerdict.Allow)

        app.controller.tapCard()
        val approved = app.phase as PaymentPhase.Approved
        assertEquals(1, approved.processorCharges)
        assertEquals(TxnState.COMPLETED, app.history().single().state)
        assertEquals(1, app.charges())
    }

    @Test
    fun `scenario 2 - overlay blocks before any payment is recorded`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.OVERLAY)

        app.controller.takePayment()

        val flagged = app.ui.screen as Screen.Flagged
        assertTrue(flagged.verdict is GuardVerdict.Block)
        assertEquals(false, flagged.canContinue)
        assertTrue(ReasonCode.SCREEN_OVERLAY_PRESENT in flagged.verdict.reasons)
        assertEquals(emptyList<Any>(), app.history())
        assertEquals(0, app.charges())
    }

    @Test
    fun `scenario 3 - screen recording blocks payment`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.RECORDING)

        app.controller.takePayment()

        val flagged = app.ui.screen as Screen.Flagged
        assertTrue(flagged.verdict is GuardVerdict.Block)
        assertTrue(ReasonCode.SCREEN_CAPTURE_ACTIVE in flagged.verdict.reasons)
    }

    @Test
    fun `scenario 4 - network drop is unknown, blocks new payments, and resolves on reconnect with one charge`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.NETWORK_DROP)
        app.controller.takePayment()
        app.controller.tapCard()

        assertTrue(app.phase is PaymentPhase.Unknown)
        assertEquals(StartupState.Pending(1), app.ui.startup)
        assertEquals(1, recoveryScheduled)

        // A new payment cannot start while this one is unresolved.
        app.controller.backToShop()
        app.controller.takePayment()
        assertEquals(Screen.Shop, app.ui.screen)

        // Reconnect (also offered on the shop screen) resolves it by idempotency key.
        app.controller.reconnect()
        assertEquals(StartupState.Ready, app.ui.startup)
        assertEquals(TxnState.COMPLETED, app.history().single().state)
        assertEquals(1, app.charges())
    }

    @Test
    fun `scenario 5 - app killed after charge is recovered on relaunch`() = runTest {
        val before = App(this).startWithCart()
        before.controller.arm(DemoScenario.APP_CRASH)
        before.controller.takePayment()
        before.controller.tapCard()
        assertEquals(1, killed)
        assertEquals(TxnState.SUBMITTED, before.history().single().state)

        // Relaunch: new objects over the same journal file and processor memory.
        val after = App(this)
        after.controller.start()

        assertEquals(StartupState.Ready, after.ui.startup)
        assertTrue(after.ui.banner!!.contains("approved, charged 1×"))
        assertEquals(TxnState.COMPLETED, after.history().single().state)
        assertEquals(1, after.charges())
    }

    @Test
    fun `timeout becomes safe to resubmit with the same key and charges once`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.TIMEOUT)
        app.controller.takePayment()
        app.controller.tapCard()
        assertTrue(app.phase is PaymentPhase.Unknown)

        app.controller.reconnect()
        assertTrue(app.phase is PaymentPhase.SafeToResubmit)
        app.controller.resubmit()

        assertEquals(1, (app.phase as PaymentPhase.Approved).processorCharges)
        assertEquals(1, app.charges())
    }

    @Test
    fun `engine crash is recovered as approved`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.ENGINE_CRASH)
        app.controller.takePayment()
        app.controller.tapCard()
        assertTrue(app.phase is PaymentPhase.Unknown)

        app.controller.reconnect()
        assertTrue(app.phase is PaymentPhase.Approved)
        assertEquals(1, app.charges())
    }

    @Test
    fun `duplicate engine response is absorbed`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.DUPLICATE_RESPONSE)
        app.controller.takePayment()
        app.controller.tapCard()
        testScheduler.advanceUntilIdle()

        assertTrue(app.phase is PaymentPhase.Approved)
        assertEquals(TxnState.COMPLETED, app.history().single().state)
        assertEquals(1, app.charges())
    }

    @Test
    fun `decline is recorded with the fake decline code`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.DECLINE)
        app.controller.takePayment()
        app.controller.tapCard()
        assertEquals(PaymentPhase.Declined("05"), app.phase)
        val record = app.history().single()
        assertEquals(TxnState.COMPLETED, record.state)
        assertEquals(dev.posguard.core.ledger.TxnOutcome.DECLINED, record.outcome)
    }

    @Test
    fun `play integrity device failure blocks`() = runTest {
        val app = App(this).startWithCart()
        app.controller.arm(DemoScenario.DEVICE_INTEGRITY_FAIL)
        app.controller.takePayment()
        val flagged = app.ui.screen as Screen.Flagged
        assertTrue(ReasonCode.DEVICE_INTEGRITY_FAIL in flagged.verdict.reasons)
    }

    @Test
    fun `reset demo clears toggles, scenarios, and history view`() = runTest {
        val app = App(this).startWithCart()
        app.controller.takePayment()
        app.controller.tapCard()
        app.controller.arm(DemoScenario.OVERLAY)
        assertEquals(setOf(DemoScenario.OVERLAY), app.ui.armed)

        app.controller.resetDemo()

        assertEquals(emptySet<DemoScenario>(), app.ui.armed)
        assertEquals(false, app.threats.overlaySimulated.value)
        assertEquals(emptyList<Any>(), app.history())
        assertEquals(0, app.charges())
    }

    @Test
    fun `scrubbed test error never shows the card number`() = runTest {
        val app = App(this)
        app.controller.logScrubbedTestError()
        app.telemetry.flush()
        val json = app.sink.events.value.single().toJson()
        assertTrue(json.contains("[REDACTED_PAN]"))
        assertEquals(false, json.replace(" ", "").contains("4111111111111111"))
    }

    // --- Scenario 7: relay-risk hold for review ---------------------------------------

    private suspend fun App.armBurstAndTap(): PaymentPhase.HeldForReview {
        controller.arm(DemoScenario.RELAY_BURST)
        controller.takePayment()
        assertTrue((ui.screen as Screen.Payment).verdict is GuardVerdict.Allow)
        controller.tapCard()
        return phase as PaymentPhase.HeldForReview
    }

    @Test
    fun `scenario 7 - third wallet tap is held for review, never declined, and charged once`() = runTest {
        val app = App(this).startWithCart()
        val held = app.armBurstAndTap()

        assertEquals(1, held.processorCharges)
        assertTrue(held.reasons.single().contains("phone-wallet taps"))
        assertEquals(TxnState.AUTHORIZED, app.ledger.get(held.txnId)!!.state)
        assertEquals(listOf(WalletBurstRule.ID), app.controller.heldForReview.value.single().hits.map { it.ruleId })
        // Two pre-run wallet payments plus the live one: three charges, one per payment.
        assertEquals(3, app.charges())
        assertEquals(3, app.engine.processedCharges().map { it.idempotencyKey }.toSet().size)
        assertEquals(listOf(TxnState.AUTHORIZED, TxnState.COMPLETED, TxnState.COMPLETED), app.history().map { it.state })

        app.controller.completeReviewed()
        assertEquals(PaymentPhase.Approved(held.engineReference, 1), app.phase)
        assertEquals(TxnState.COMPLETED, app.ledger.get(held.txnId)!!.state)
        assertTrue(app.controller.heldForReview.value.isEmpty())
        assertEquals(emptySet<DemoScenario>(), app.ui.armed)
    }

    @Test
    fun `skipping the review makes the next preflight warn until it is reviewed`() = runTest {
        val app = App(this).startWithCart()
        val held = app.armBurstAndTap()
        app.controller.finishSale() // "Review later"

        app.controller.add("coffee")
        app.controller.takePayment()
        val flagged = app.ui.screen as Screen.Flagged
        assertTrue(flagged.verdict is GuardVerdict.Warn)
        assertEquals(listOf(ReasonCode.RELAY_RISK_PATTERN), flagged.verdict.reasons)
        assertTrue(flagged.canContinue)

        app.controller.backToShop()
        app.controller.markReviewed(held.txnId)
        assertEquals(TxnState.COMPLETED, app.ledger.get(held.txnId)!!.state)
        app.controller.takePayment()
        assertTrue((app.ui.screen as Screen.Payment).verdict is GuardVerdict.Allow)
    }

    @Test
    fun `reset demo clears payments held for review`() = runTest {
        val app = App(this).startWithCart()
        app.armBurstAndTap()
        app.controller.resetDemo()
        assertTrue(app.controller.heldForReview.value.isEmpty())
        app.controller.add("coffee")
        app.controller.takePayment()
        assertTrue((app.ui.screen as Screen.Payment).verdict is GuardVerdict.Allow)
    }

    @Test
    fun `normal card taps are never held`() = runTest {
        val app = App(this).startWithCart()
        repeat(4) {
            app.controller.add("coffee")
            app.controller.takePayment()
            app.controller.tapCard()
            assertTrue(app.phase is PaymentPhase.Approved)
            app.controller.finishSale()
        }
        assertTrue(app.controller.heldForReview.value.isEmpty())
    }

    // --- Scenario 9: EBT purchase (simulator) ---------------------------------------------

    private suspend fun App.toEbtSplit(): PaymentPhase.EbtSplit {
        controller.takePayment()
        assertTrue((ui.screen as Screen.Payment).verdict is GuardVerdict.Allow)
        controller.startEbt()
        return phase as PaymentPhase.EbtSplit
    }

    @Test
    fun `scenario 9 - split, chip fallback to tap, partial SNAP approval, rest by card, masked receipt`() = runTest {
        val app = App(this)
        app.controller.start()
        app.controller.arm(DemoScenario.EBT_PURCHASE)
        assertEquals(CheckoutController.SCENARIO_9_BASKET, app.ui.cart)

        val split = app.toEbtSplit()
        assertEquals(797L, split.split.snapEligibleCents) // bananas x2 + juice
        assertEquals(874L, split.split.otherCents) // soda (restricted) + soap + hot coffee
        assertEquals(listOf(Tender.EBT_SNAP, Tender.CARD), split.legs.map { it.tender })

        app.controller.payEbt()
        val rest = app.phase as PaymentPhase.EbtRemainder
        assertTrue(rest.snap.isPartial)
        assertEquals(Money(500, "USD"), rest.snap.approved)
        assertEquals(Money(874 + 297, "USD"), rest.remaining.amount)
        assertTrue(rest.log.any { it.startsWith("Chip insert failed") && it.contains("Tap card") })

        app.controller.payRemainderByCard()
        val done = app.phase as PaymentPhase.EbtDone
        assertTrue(done.receiptLines.contains(EbtReceipt.BALANCE_ON_RECEIPT))
        assertTrue(done.receiptLines.none { it.startsWith("SNAP balance") })
        assertTrue(done.receiptLines.any { it.contains("(partial)") })

        val history = app.history()
        val snap = history.single { it.tender == Tender.EBT_SNAP }
        val card = history.single { it.tender == Tender.CARD }
        assertEquals(TxnState.COMPLETED, snap.state)
        assertEquals(Money(500, "USD"), snap.approvedAmount)
        assertEquals(TxnState.COMPLETED, card.state)
        assertEquals(Money(1171, "USD"), card.amount)
        assertEquals(1, app.charges())
        assertEquals(1, app.ebtSim.processed().size)

        testScheduler.advanceUntilIdle()
        val types = app.sink.events.value.map { it.type }
        assertTrue(EventType.EBT_ENTRY_FALLBACK in types)
        assertTrue(EventType.EBT_PARTIAL_APPROVAL in types)
        assertTrue(app.ui.armed.isEmpty())
    }

    @Test
    fun `magstripe-only card falls back to swipe and an all-SNAP basket needs no card leg`() = runTest {
        val app = App(this)
        app.controller.start()
        app.controller.arm(DemoScenario.EBT_MAGSTRIPE_CARD)
        app.controller.add("bananas")
        app.toEbtSplit()

        app.controller.payEbt()
        val done = app.phase as PaymentPhase.EbtDone
        assertTrue(done.log.any { it.contains("card has no chip") && it.contains("Swipe card") })
        assertEquals(0, app.charges())
        assertEquals(TxnState.COMPLETED, app.history().single().state)
    }

    @Test
    fun `reader without the SNAP chip app goes straight to swipe`() = runTest {
        val app = App(this)
        app.controller.start()
        app.controller.arm(DemoScenario.EBT_CHIP_APP_UNKNOWN)
        app.controller.add("juice")
        app.toEbtSplit()

        app.controller.payEbt()
        val done = app.phase as PaymentPhase.EbtDone
        assertEquals(4, done.log.size) // insert prompt, swipe prompt, PIN note, approval
        assertTrue(done.log[1].contains("chip app not recognized") && done.log[1].contains("Swipe card"))
    }

    @Test
    fun `empty SNAP balance is declined and nothing is charged`() = runTest {
        val app = App(this)
        app.controller.start()
        app.controller.arm(DemoScenario.EBT_NO_BALANCE)
        app.controller.add("bananas")
        app.toEbtSplit()

        app.controller.payEbt()
        assertTrue(app.phase is PaymentPhase.EbtFailed)
        val record = app.history().single()
        assertEquals(TxnOutcome.DECLINED, record.outcome)
        assertEquals(0, app.charges())
    }

    @Test
    fun `EBT leg lost after submit is recovered from the EBT engine, never voided`() = runTest {
        val app = App(this)
        app.controller.start()
        app.controller.add("bananas")
        app.toEbtSplit()
        app.ebtSim.setNextScenario(EbtSimScenario.NETWORK_DROP_AFTER_SUBMIT)

        app.controller.payEbt()
        val unknown = app.phase as PaymentPhase.Unknown
        assertEquals(TxnState.UNKNOWN, app.ledger.get(unknown.txnId)!!.state)

        app.controller.reconnect()
        val record = app.ledger.get(unknown.txnId)!!
        assertEquals(TxnState.COMPLETED, record.state)
        assertEquals(TxnOutcome.APPROVED, record.outcome)
        assertTrue(app.phase is PaymentPhase.Approved)
        assertEquals(1, app.ebtSim.processed().size)
    }
}
