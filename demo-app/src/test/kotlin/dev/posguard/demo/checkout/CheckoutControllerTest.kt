package dev.posguard.demo.checkout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.core.verdict.ReasonCode
import dev.posguard.demo.DemoThreats
import dev.posguard.demo.di.DemoModule
import dev.posguard.integrity.play.FakeIntegrityVerdictSource
import dev.posguard.integrity.play.PlayIntegritySignals
import dev.posguard.ledger.PosGuardLedger
import dev.posguard.mockengine.MockPaymentEngine
import dev.posguard.mockengine.SharedPreferencesMockProcessorStore
import dev.posguard.telemetry.DeviceContext
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
        val ledger: TransactionLedger = PosGuardLedger.Builder(context, engine).databaseName(dbName).build()
        val threats = DemoThreats()
        val integrityServer = FakeIntegrityVerdictSource()
        val policy: GuardPolicy = context.assets.open(DemoModule.DEMO_POLICY_ASSET)
            .use { GuardPolicy.fromProperties(Properties().apply { load(it) }) }
        val guard: PosGuard = PosGuard.Builder(ledger)
            .signals(PlayIntegritySignals.create(integrityServer, ttl = 1.seconds))
            .signals(threats.signals)
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
}
