package dev.posguard.demo

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.ReasonCode
import dev.posguard.demo.checkout.CheckoutController
import dev.posguard.demo.checkout.ResetStore
import dev.posguard.integrity.play.FakeIntegrityVerdictSource
import dev.posguard.integrity.relay.RelayRiskMonitor
import dev.posguard.ledger.LedgerRecovery
import dev.posguard.mockengine.MockPaymentEngine
import dev.posguard.telemetry.InMemorySink
import dev.posguard.telemetry.PosGuardTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    app: Application,
    guard: PosGuard,
    ledger: TransactionLedger,
    engine: MockPaymentEngine,
    threats: DemoThreats,
    integrityServer: FakeIntegrityVerdictSource,
    telemetry: PosGuardTelemetry,
    events: InMemorySink,
    relay: RelayRiskMonitor,
    private val policy: GuardPolicy,
) : ViewModel() {

    val controller = CheckoutController(
        scope = viewModelScope,
        guard = guard,
        ledger = ledger,
        engine = engine,
        threats = threats,
        integrityServer = integrityServer,
        telemetry = telemetry,
        events = events,
        relay = relay,
        resetStore = PrefsResetStore(app),
        scheduleRecovery = { LedgerRecovery.schedule(app) },
        // Scenario 5: really kill the process, exactly like a crash mid-payment.
        processKiller = { Process.killProcess(Process.myPid()) },
    )

    init {
        run { start() }
    }

    /** The demo policy's action for [reason], for the Why-blocked panel. */
    fun actionFor(reason: ReasonCode): GuardAction = policy.actionFor(reason)

    /** Runs a controller action off the UI thread. Errors become a banner, not a crash. */
    fun run(block: suspend CheckoutController.() -> Unit) {
        viewModelScope.launch {
            try {
                controller.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.showBanner("Unexpected error: ${e::class.simpleName}")
            }
        }
    }

    private class PrefsResetStore(context: Context) : ResetStore {
        private val prefs = context.getSharedPreferences("sunrise-demo", Context.MODE_PRIVATE)
        override var resetAtMillis: Long
            get() = prefs.getLong(KEY, 0L)
            set(value) {
                prefs.edit().putLong(KEY, value).apply()
            }

        private companion object {
            const val KEY = "reset_at_millis"
        }
    }
}
