package dev.posguard.core

import dev.posguard.core.event.GuardEvent
import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import kotlin.time.Duration

internal class DefaultPosGuard(
    override val ledger: TransactionLedger,
    private val signals: List<IntegritySignal>,
    private val policy: GuardPolicy,
    private val signalTimeout: Duration,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val listeners: List<GuardEventListener>,
) : PosGuard {

    override suspend fun preflight(): GuardVerdict = withContext(dispatcher) {
        val results = coroutineScope {
            signals.map { signal -> async { runSafely(signal) } }.awaitAll()
        }
        val verdict = VerdictEvaluator.evaluate(results, policy, clock.instant())
        notify(GuardEvent.PreflightEvaluated(verdict))
        verdict
    }

    /** Never throws for signal failures. Only real cancellation of the caller propagates. */
    private suspend fun runSafely(signal: IntegritySignal): SignalResult = try {
        withTimeoutOrNull(signalTimeout) { signal.check() }
            ?: SignalResult.Unavailable(signal.id, "timeout")
    } catch (e: CancellationException) {
        // If the caller was cancelled, propagate. Otherwise the signal threw a
        // stray CancellationException (for example its own inner timeout), which
        // counts as unavailable.
        currentCoroutineContext().ensureActive()
        SignalResult.Unavailable(signal.id, "cancelled")
    } catch (e: Exception) {
        // Only the exception type is kept. Messages may contain sensitive data.
        SignalResult.Unavailable(signal.id, "error: ${e::class.simpleName}")
    }

    private fun notify(event: GuardEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (_: Exception) {
                // A listener must never break checkout.
            }
        }
    }
}
