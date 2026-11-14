package dev.posguard.integrity

import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the built-in integrity signals. Supply it from outside the
 * library (code or [fromProperties]). Defaults are documented and safe, not
 * tuned.
 */
public data class IntegrityConfig(
    /**
     * Package names of accessibility services treated as trusted assistive
     * tools. `isAccessibilityTool` is self-declared by apps, so it is *not*
     * used for trust.
     */
    val accessibilityAllowlist: Set<String> = DEFAULT_ACCESSIBILITY_ALLOWLIST,
    /** How long an obscured touch keeps the overlay signal raised. */
    val obscuredTouchWindow: Duration = DEFAULT_OBSCURED_TOUCH_WINDOW,
) {
    init {
        require(obscuredTouchWindow.isPositive()) { "obscuredTouchWindow must be positive." }
    }

    public companion object {
        /** Common assistive tools, so merchants using them are not flagged. Extend for your fleet. */
        public val DEFAULT_ACCESSIBILITY_ALLOWLIST: Set<String> = setOf(
            "com.google.android.marvin.talkback", // TalkBack, Select to Speak, Switch Access, Accessibility Menu
            "com.google.android.accessibility.switchaccess",
            "com.google.android.accessibility.selecttospeak",
            "com.google.android.apps.accessibility.voiceaccess",
            "com.samsung.android.accessibility.talkback",
        )

        public val DEFAULT_OBSCURED_TOUCH_WINDOW: Duration = 30.seconds

        public const val PROPERTY_ALLOWLIST: String = "posguard.integrity.accessibilityAllowlist"
        public const val PROPERTY_ALLOWLIST_EXTRA: String = "posguard.integrity.accessibilityAllowlist.extra"
        public const val PROPERTY_TOUCH_WINDOW_SECONDS: String = "posguard.integrity.obscuredTouchWindowSeconds"

        /**
         * Reads optional overrides:
         * - `posguard.integrity.accessibilityAllowlist`: replaces the default list (comma-separated)
         * - `posguard.integrity.accessibilityAllowlist.extra`: adds to the default list
         * - `posguard.integrity.obscuredTouchWindowSeconds`: positive integer
         *
         * @throws IllegalArgumentException on malformed values.
         */
        public fun fromProperties(properties: Properties): IntegrityConfig {
            fun list(key: String): Set<String>? = properties.getProperty(key)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()

            val base = list(PROPERTY_ALLOWLIST) ?: DEFAULT_ACCESSIBILITY_ALLOWLIST
            val window = properties.getProperty(PROPERTY_TOUCH_WINDOW_SECONDS)?.trim()?.let {
                val seconds = requireNotNull(it.toLongOrNull()) { "$PROPERTY_TOUCH_WINDOW_SECONDS must be an integer." }
                seconds.seconds
            } ?: DEFAULT_OBSCURED_TOUCH_WINDOW
            return IntegrityConfig(base + (list(PROPERTY_ALLOWLIST_EXTRA) ?: emptySet()), window)
        }
    }
}
