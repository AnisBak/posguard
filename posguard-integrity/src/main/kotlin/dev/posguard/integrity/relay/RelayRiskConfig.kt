package dev.posguard.integrity.relay

import dev.posguard.core.ledger.Money
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Settings for the built-in relay-risk rules. Supply them from outside the
 * library (code or [fromProperties]). The defaults are documented starting
 * points, **not tuned values**. Only the wallet-burst rule is on by default.
 */
public data class RelayRiskConfig(
    val walletBurstEnabled: Boolean = true,
    val walletBurstCount: Int = DEFAULT_WALLET_BURST_COUNT,
    val walletBurstWindow: Duration = DEFAULT_WALLET_BURST_WINDOW,
    /** The no-CVM (no PIN or signature) limit for contactless. Null turns the near-limit rule off. */
    val noCvmLimit: Money? = null,
    val nearCvmLimitPercent: Int = DEFAULT_NEAR_CVM_LIMIT_PERCENT,
    val nearCvmLimitCount: Int = DEFAULT_NEAR_CVM_LIMIT_COUNT,
    val nearCvmLimitWindow: Duration = DEFAULT_NEAR_CVM_LIMIT_WINDOW,
    /** Card reads at least this long are flagged. Null (the default) turns the rule off. */
    val slowCardRead: Duration? = null,
    /** How many recent payments the monitor remembers. */
    val historySize: Int = DEFAULT_HISTORY_SIZE,
    /** How long the monitor remembers a payment. Must cover the longest rule window. */
    val historyRetention: Duration = DEFAULT_HISTORY_RETENTION,
) {
    init {
        require(walletBurstCount >= 2) { "walletBurstCount must be at least 2." }
        require(walletBurstWindow.isPositive()) { "walletBurstWindow must be positive." }
        require(nearCvmLimitPercent in 1..50) { "nearCvmLimitPercent must be between 1 and 50." }
        require(nearCvmLimitCount >= 2) { "nearCvmLimitCount must be at least 2." }
        require(nearCvmLimitWindow.isPositive()) { "nearCvmLimitWindow must be positive." }
        require(slowCardRead == null || slowCardRead.isPositive()) { "slowCardRead must be positive." }
        require(historySize >= 1) { "historySize must be at least 1." }
        require(historyRetention >= walletBurstWindow && historyRetention >= nearCvmLimitWindow) {
            "historyRetention must cover every rule window."
        }
    }

    /** The built-in rules this config turns on. */
    public fun defaultRules(): List<RelayRiskRule> = buildList {
        if (walletBurstEnabled) add(WalletBurstRule(walletBurstCount, walletBurstWindow))
        noCvmLimit?.let { add(NearCvmLimitBurstRule(it, nearCvmLimitPercent, nearCvmLimitCount, nearCvmLimitWindow)) }
        slowCardRead?.let { add(SlowCardReadRule(it.inWholeMilliseconds)) }
    }

    public companion object {
        public const val DEFAULT_WALLET_BURST_COUNT: Int = 5
        public val DEFAULT_WALLET_BURST_WINDOW: Duration = 60.seconds
        public const val DEFAULT_NEAR_CVM_LIMIT_PERCENT: Int = 10
        public const val DEFAULT_NEAR_CVM_LIMIT_COUNT: Int = 3
        public val DEFAULT_NEAR_CVM_LIMIT_WINDOW: Duration = 60.seconds
        public const val DEFAULT_HISTORY_SIZE: Int = 50
        public val DEFAULT_HISTORY_RETENTION: Duration = 10.minutes

        /** Prefix for keys read by [fromProperties]. */
        public const val PROPERTY_PREFIX: String = "posguard.relay."

        private const val WALLET_ENABLED = "wallet_burst.enabled"
        private const val WALLET_COUNT = "wallet_burst.count"
        private const val WALLET_WINDOW = "wallet_burst.window_seconds"
        private const val CVM_LIMIT_CENTS = "near_cvm_limit.limit_cents"
        private const val CVM_CURRENCY = "near_cvm_limit.currency"
        private const val CVM_PERCENT = "near_cvm_limit.percent"
        private const val CVM_COUNT = "near_cvm_limit.count"
        private const val CVM_WINDOW = "near_cvm_limit.window_seconds"
        private const val SLOW_READ_MILLIS = "slow_card_read.millis"
        private const val HISTORY_SIZE = "history.size"
        private const val HISTORY_RETENTION = "history.retention_seconds"

        private val KNOWN_KEYS = setOf(
            WALLET_ENABLED, WALLET_COUNT, WALLET_WINDOW, CVM_LIMIT_CENTS, CVM_CURRENCY, CVM_PERCENT,
            CVM_COUNT, CVM_WINDOW, SLOW_READ_MILLIS, HISTORY_SIZE, HISTORY_RETENTION,
        )

        /**
         * Reads optional overrides with the `posguard.relay.` prefix:
         *
         * | Key | Value |
         * |---|---|
         * | `wallet_burst.enabled` | `true` / `false` |
         * | `wallet_burst.count`, `wallet_burst.window_seconds` | positive integers |
         * | `near_cvm_limit.limit_cents` + `near_cvm_limit.currency` | both together turn the rule on |
         * | `near_cvm_limit.percent`, `.count`, `.window_seconds` | integers |
         * | `slow_card_read.millis` | positive integer, turns the rule on |
         * | `history.size`, `history.retention_seconds` | positive integers |
         *
         * Keys without the prefix are ignored, so one file can hold several configs.
         *
         * @throws IllegalArgumentException on unknown keys or malformed values.
         */
        public fun fromProperties(properties: Properties): RelayRiskConfig {
            val values = properties.stringPropertyNames()
                .filter { it.startsWith(PROPERTY_PREFIX) }
                .associate { it.removePrefix(PROPERTY_PREFIX) to properties.getProperty(it).trim() }
            values.keys.firstOrNull { it !in KNOWN_KEYS }?.let {
                throw IllegalArgumentException("Unknown relay-risk key '$PROPERTY_PREFIX$it'.")
            }

            fun long(key: String): Long? = values[key]?.let {
                requireNotNull(it.toLongOrNull()) { "$PROPERTY_PREFIX$key must be an integer." }
            }
            fun int(key: String): Int? = long(key)?.let {
                require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$PROPERTY_PREFIX$key is out of range." }
                it.toInt()
            }
            fun bool(key: String): Boolean? = values[key]?.let {
                when (it.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("$PROPERTY_PREFIX$key must be true or false.")
                }
            }

            val limitCents = long(CVM_LIMIT_CENTS)
            val currency = values[CVM_CURRENCY]
            require((limitCents == null) == (currency == null)) {
                "Set both $PROPERTY_PREFIX$CVM_LIMIT_CENTS and $PROPERTY_PREFIX$CVM_CURRENCY, or neither."
            }

            val defaults = RelayRiskConfig()
            return RelayRiskConfig(
                walletBurstEnabled = bool(WALLET_ENABLED) ?: defaults.walletBurstEnabled,
                walletBurstCount = int(WALLET_COUNT) ?: defaults.walletBurstCount,
                walletBurstWindow = long(WALLET_WINDOW)?.seconds ?: defaults.walletBurstWindow,
                noCvmLimit = limitCents?.let { Money(it, currency!!) },
                nearCvmLimitPercent = int(CVM_PERCENT) ?: defaults.nearCvmLimitPercent,
                nearCvmLimitCount = int(CVM_COUNT) ?: defaults.nearCvmLimitCount,
                nearCvmLimitWindow = long(CVM_WINDOW)?.seconds ?: defaults.nearCvmLimitWindow,
                slowCardRead = long(SLOW_READ_MILLIS)?.milliseconds,
                historySize = int(HISTORY_SIZE) ?: defaults.historySize,
                historyRetention = long(HISTORY_RETENTION)?.seconds ?: defaults.historyRetention,
            )
        }
    }
}
