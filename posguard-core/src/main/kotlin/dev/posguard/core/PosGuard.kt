package dev.posguard.core

import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.verdict.GuardVerdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point for an integrating POS app.
 *
 * ```
 * val guard = PosGuard.Builder(ledger)
 *     .signals(overlaySignal, captureSignal, playIntegritySignal)
 *     .policy(GuardPolicy.fromProperties(config))
 *     .addListener(telemetry)
 *     .build()
 *
 * when (val verdict = guard.preflight()) {
 *     is GuardVerdict.Block -> showWhyBlocked(verdict.reasons)
 *     is GuardVerdict.Warn -> { showNotice(verdict.reasons); openPayment() }
 *     is GuardVerdict.Allow -> openPayment()
 * }
 * ```
 */
public interface PosGuard {
    /**
     * Runs every integrity signal and applies the policy. Call it before opening
     * payment acceptance. Main-safe. Never throws because a signal failed:
     * failures become [dev.posguard.core.verdict.ReasonCode.INTEGRITY_UNAVAILABLE].
     */
    public suspend fun preflight(): GuardVerdict

    /** The outage-safe transaction journal. */
    public val ledger: TransactionLedger

    /** Builds the default [PosGuard]. Plain constructor injection, no DI framework required. */
    public class Builder(private val ledger: TransactionLedger) {
        private val signals = mutableListOf<IntegritySignal>()
        private val listeners = mutableListOf<GuardEventListener>()
        private var policy: GuardPolicy = GuardPolicy.Defaults
        private var signalTimeout: Duration = DEFAULT_SIGNAL_TIMEOUT
        private var dispatcher: CoroutineDispatcher = Dispatchers.Default
        private var clock: Clock = Clock.systemUTC()

        /** Adds signals. Ids must be unique. */
        public fun signals(vararg signals: IntegritySignal): Builder = apply { this.signals += signals }

        /** Adds signals. Ids must be unique. */
        public fun signals(signals: Iterable<IntegritySignal>): Builder = apply { this.signals += signals }

        /** Policy mapping reasons to actions. Defaults to [GuardPolicy.Defaults]. */
        public fun policy(policy: GuardPolicy): Builder = apply { this.policy = policy }

        /** Maximum time per signal before it counts as unavailable. Defaults to 3 seconds. */
        public fun signalTimeout(timeout: Duration): Builder = apply {
            require(timeout.isPositive()) { "signalTimeout must be positive." }
            signalTimeout = timeout
        }

        /** Dispatcher for running signals. Defaults to [Dispatchers.Default]. */
        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        /** Clock for verdict timestamps. Defaults to the UTC system clock. */
        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Adds an event listener, for example a telemetry sink. */
        public fun addListener(listener: GuardEventListener): Builder = apply { listeners += listener }

        public fun build(): PosGuard {
            val duplicates = signals.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "Duplicate signal ids: $duplicates" }
            return DefaultPosGuard(
                ledger = ledger,
                signals = signals.toList(),
                policy = policy,
                signalTimeout = signalTimeout,
                dispatcher = dispatcher,
                clock = clock,
                listeners = listeners.toList(),
            )
        }
    }

    public companion object {
        public val DEFAULT_SIGNAL_TIMEOUT: Duration = 3.seconds
    }
}
