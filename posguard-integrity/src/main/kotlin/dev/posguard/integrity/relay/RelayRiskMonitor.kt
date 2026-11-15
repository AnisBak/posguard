package dev.posguard.integrity.relay

import dev.posguard.core.event.GuardEvent
import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import kotlin.time.toJavaDuration

/**
 * **Experimental.** Runs relay-risk rules on each authorized payment and keeps a
 * review queue.
 *
 * Usage:
 * 1. Add [signal] to `PosGuard.Builder`, so `preflight()` warns while a flagged
 *    payment awaits review. `RELAY_RISK_PATTERN` is capped at WARN.
 * 2. After the engine authorizes a payment, call [record] with metadata your
 *    adapter maps from the engine.
 * 3. On [RelayRiskAssessment.Flagged], hold the sale: show the reasons and ask
 *    the merchant to check the customer before handing over goods. **Never
 *    decline or reverse on this alone.** Then call [markReviewed].
 *
 * History lives in memory and is bounded by [RelayRiskConfig.historySize] and
 * [RelayRiskConfig.historyRetention], so a process restart starts a fresh window.
 * A rule that throws is skipped, and the other rules still run.
 */
public class RelayRiskMonitor(
    private val config: RelayRiskConfig = RelayRiskConfig(),
    rules: List<RelayRiskRule> = config.defaultRules(),
    listeners: List<GuardEventListener> = emptyList(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val rules = rules.toList()
    private val listeners = listeners.toList()
    private val lock = Any()
    private val history = ArrayDeque<TapMetadata>()
    private val pending = MutableStateFlow<List<FlaggedTap>>(emptyList())

    init {
        require(this.rules.map { it.id }.toSet().size == this.rules.size) { "Rule ids must be unique." }
    }

    /** Flagged payments waiting for the merchant's review, oldest first. */
    public val pendingReview: StateFlow<List<FlaggedTap>> = pending.asStateFlow()

    /**
     * Raises `RELAY_RISK_PATTERN` while any payment awaits review. Add it to
     * `PosGuard.Builder`.
     */
    public val signal: IntegritySignal = object : IntegritySignal {
        override val id: String = SIGNAL_ID

        override suspend fun check(): SignalResult {
            val waiting = pending.value.size
            return if (waiting == 0) {
                SignalResult.Clear(id)
            } else {
                SignalResult.Detected(id, ReasonCode.RELAY_RISK_PATTERN, "pending_review=$waiting")
            }
        }
    }

    /**
     * Evaluates one authorized payment and remembers it. Recording the same
     * [TapMetadata.txnId] again (for example, after a duplicate engine response)
     * doesn't count it twice. It returns the earlier flag if still pending.
     */
    public fun record(tap: TapMetadata): RelayRiskAssessment {
        val hits: List<RuleHit>
        synchronized(lock) {
            if (history.any { it.txnId == tap.txnId }) {
                return pending.value.firstOrNull { it.txnId == tap.txnId }
                    ?.let { RelayRiskAssessment.Flagged(it.hits) }
                    ?: RelayRiskAssessment.Clear
            }
            prune()
            val recent = history.toList()
            hits = rules.mapNotNull { rule -> runRule(rule, tap, recent) }
            history.addLast(tap)
            while (history.size > config.historySize) history.removeFirst()
            if (hits.isNotEmpty()) pending.value = pending.value + FlaggedTap(tap.txnId, tap.at, hits)
        }
        if (hits.isEmpty()) return RelayRiskAssessment.Clear
        emit(GuardEvent.RelayRiskFlagged(tap.txnId, hits.map { it.ruleId }, clock.instant()))
        return RelayRiskAssessment.Flagged(hits)
    }

    /** The merchant reviewed [txnId]. Returns false if it wasn't waiting for review. */
    public fun markReviewed(txnId: TxnId): Boolean {
        val removed = synchronized(lock) {
            val before = pending.value
            pending.value = before.filterNot { it.txnId == txnId }
            pending.value.size != before.size
        }
        if (removed) emit(GuardEvent.RelayRiskReviewed(txnId, clock.instant()))
        return removed
    }

    /** Forgets all history and pending reviews without emitting events. For demos and tests. */
    public fun clear(): Unit = synchronized(lock) {
        history.clear()
        pending.value = emptyList()
    }

    private fun prune() {
        val cutoff = clock.instant().minus(config.historyRetention.toJavaDuration())
        while (history.isNotEmpty() && history.first().at.isBefore(cutoff)) history.removeFirst()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runRule(rule: RelayRiskRule, tap: TapMetadata, recent: List<TapMetadata>): RuleHit? = try {
        rule.evaluate(tap, recent)
    } catch (_: Exception) {
        null // Fail safe: a broken rule never blocks checkout.
    }

    @Suppress("TooGenericExceptionCaught")
    private fun emit(event: GuardEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (_: Exception) {
                // Listeners can never break checkout.
            }
        }
    }

    public companion object {
        public const val SIGNAL_ID: String = "relay.pending_review"
    }
}
