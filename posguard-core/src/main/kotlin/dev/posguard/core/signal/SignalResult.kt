package dev.posguard.core.signal

import dev.posguard.core.verdict.ReasonCode

/**
 * Outcome of one [IntegritySignal] check.
 *
 * `detail` fields are short, non-sensitive diagnostics (for example
 * `"api=34"` or `"package allowlist miss"`). They must never contain user
 * content, screen content, card data, or personal data.
 */
public sealed interface SignalResult {
    /** The [IntegritySignal.id] that produced this result. */
    public val signalId: String

    /** The reason code this result contributes to the verdict, or null if clear. */
    public val reason: ReasonCode?

    /** The signal ran and found nothing. */
    public data class Clear(override val signalId: String) : SignalResult {
        override val reason: ReasonCode? get() = null
    }

    /** The signal ran and detected a risk. */
    public data class Detected(
        override val signalId: String,
        override val reason: ReasonCode,
        val detail: String? = null,
    ) : SignalResult {
        init {
            require(reason != ReasonCode.INTEGRITY_UNAVAILABLE) {
                "Use SignalResult.Unavailable for INTEGRITY_UNAVAILABLE."
            }
        }
    }

    /**
     * The signal could not run: unsupported API level, missing service, timeout,
     * or an unexpected error. Always maps to [ReasonCode.INTEGRITY_UNAVAILABLE].
     *
     * [detail] is a short non-sensitive description. The original exception is not
     * kept, so stack traces and messages cannot leak into telemetry.
     */
    public data class Unavailable(
        override val signalId: String,
        val detail: String? = null,
    ) : SignalResult {
        override val reason: ReasonCode get() = ReasonCode.INTEGRITY_UNAVAILABLE
    }
}
