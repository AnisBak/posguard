package dev.posguard.core

import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.GuardVerdict
import java.time.Instant

/**
 * Pure function from signal results and a policy to a verdict. The strictest
 * action among the detected reasons wins. With no reasons, the verdict is
 * [GuardVerdict.Allow].
 *
 * Public so apps and tools (the demo's "Why blocked" panel, tests, and the
 * assessment tool) can evaluate results without running signals.
 */
public object VerdictEvaluator {
    public fun evaluate(
        results: List<SignalResult>,
        policy: GuardPolicy,
        evaluatedAt: Instant,
    ): GuardVerdict {
        val reasons = results.mapNotNull { it.reason }.distinct()
        val action = reasons.maxOfOrNull(policy::actionFor) ?: GuardAction.ALLOW
        return when (action) {
            GuardAction.ALLOW -> GuardVerdict.Allow(reasons, results, evaluatedAt)
            GuardAction.WARN -> GuardVerdict.Warn(reasons, results, evaluatedAt)
            GuardAction.BLOCK -> GuardVerdict.Block(reasons, results, evaluatedAt)
        }
    }
}
