package dev.posguard.core.verdict

/**
 * Why a preflight check flagged a risk.
 *
 * [code] is the stable identifier used in policy config and telemetry. It never
 * changes once released, even if the enum constant is renamed.
 * [description] is a default plain-English explanation for "Why blocked / flagged"
 * screens. Apps may show their own (for example, localized) text instead.
 *
 * Adding a constant is a minor-version change. Consumers that use exhaustive
 * `when` over this enum should keep an `else` branch.
 */
public enum class ReasonCode(
    public val code: String,
    public val description: String,
) {
    DEVICE_INTEGRITY_FAIL(
        "DEVICE_INTEGRITY_FAIL",
        "This device did not pass the device integrity check. It may be modified or not a genuine certified device.",
    ),
    APP_INTEGRITY_FAIL(
        "APP_INTEGRITY_FAIL",
        "This app did not pass the app integrity check. It may have been modified or installed from an unknown source.",
    ),
    ROOT_OR_EMULATOR_SUSPECTED(
        "ROOT_OR_EMULATOR_SUSPECTED",
        "This device looks rooted or looks like an emulator. This is a heuristic signal, not proof.",
    ),
    SCREEN_OVERLAY_PRESENT(
        "SCREEN_OVERLAY_PRESENT",
        "Another app is drawing over the screen. It could hide or fake what the customer sees.",
    ),
    SCREEN_CAPTURE_ACTIVE(
        "SCREEN_CAPTURE_ACTIVE",
        "The screen is being recorded or captured. Payment screens must not be recorded.",
    ),
    RISKY_ACCESSIBILITY_SERVICE(
        "RISKY_ACCESSIBILITY_SERVICE",
        "An accessibility service that is not on the allowlist is turned on. These services can read and control the screen.",
    ),
    REMOTE_CONTROL_RISK(
        "REMOTE_CONTROL_RISK",
        "An app that can remotely view or control this device is active.",
    ),
    DEBUGGER_ATTACHED(
        "DEBUGGER_ATTACHED",
        "A debugger is attached to this app. That is not expected on a device taking real payments.",
    ),
    RELAY_RISK_PATTERN(
        "RELAY_RISK_PATTERN",
        "Recent taps match a pattern linked to relay fraud. This is experimental and flagged for review only.",
    ),
    INTEGRITY_UNAVAILABLE(
        "INTEGRITY_UNAVAILABLE",
        "A security check could not run (offline, timed out, or not supported on this device).",
    ),
    ;

    public companion object {
        /** Looks up a reason by its stable [code]. Returns null if unknown. */
        public fun fromCode(code: String): ReasonCode? = entries.firstOrNull { it.code == code }
    }
}
