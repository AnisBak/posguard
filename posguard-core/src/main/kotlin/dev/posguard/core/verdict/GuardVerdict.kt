package dev.posguard.core.verdict

import dev.posguard.core.signal.SignalResult
import java.time.Instant

/**
 * Result of [dev.posguard.core.PosGuard.preflight]: whether payment acceptance
 * may open.
 *
 * [reasons] lists every reason code that was detected, including reasons the
 * policy maps to [GuardAction.ALLOW], so apps can still log them. [signals]
 * holds the raw result of each signal for diagnostics.
 */
public sealed interface GuardVerdict {
    public val action: GuardAction
    public val reasons: List<ReasonCode>
    public val signals: List<SignalResult>
    public val evaluatedAt: Instant

    /** No reason requires a warning or a block. */
    public data class Allow(
        override val reasons: List<ReasonCode>,
        override val signals: List<SignalResult>,
        override val evaluatedAt: Instant,
    ) : GuardVerdict {
        override val action: GuardAction get() = GuardAction.ALLOW
    }

    /** Proceed, but show a notice and log it. */
    public data class Warn(
        override val reasons: List<ReasonCode>,
        override val signals: List<SignalResult>,
        override val evaluatedAt: Instant,
    ) : GuardVerdict {
        override val action: GuardAction get() = GuardAction.WARN
    }

    /** Do not open payment acceptance. */
    public data class Block(
        override val reasons: List<ReasonCode>,
        override val signals: List<SignalResult>,
        override val evaluatedAt: Instant,
    ) : GuardVerdict {
        override val action: GuardAction get() = GuardAction.BLOCK
    }
}
