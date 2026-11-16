package dev.posguard.assess.rules

import dev.posguard.assess.AssessRule

/** The rules that ship with posguard-assess, in report order. */
internal object BuiltInRules {
    fun all(): List<AssessRule> = listOf(
        PaymentScreenNoFlagSecure,
        PaymentScreenNoTouchFilter,
        NoPreflightIntegrity,
        CardDataInLogs,
        PaymentStatePlainPrefs,
        NoIdempotencyKey,
        EbtMagstripeFirst,
        OldTargetSdk,
        DeprecatedAndroidApi,
        ManifestDebuggable,
        ManifestAllowBackup,
        CleartextTraffic,
    )
}
