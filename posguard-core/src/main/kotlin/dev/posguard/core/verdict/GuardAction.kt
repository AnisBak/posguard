package dev.posguard.core.verdict

/**
 * What to do about a reason code. Declared in order of increasing severity, so
 * `maxOf` picks the strictest action.
 */
public enum class GuardAction {
    /** Proceed silently. The reason is still reported in the verdict. */
    ALLOW,

    /** Proceed, show a notice, and log it. */
    WARN,

    /** Do not open payment acceptance. */
    BLOCK,
}
