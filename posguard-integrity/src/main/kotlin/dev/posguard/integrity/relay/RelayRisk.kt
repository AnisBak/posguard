package dev.posguard.integrity.relay

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.TxnId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/*
 * EXPERIMENTAL. Relay-risk flags are rule-based hints, not proof. They flag an
 * authorized payment for the merchant to review and never decline anything
 * (GuardPolicy caps RELAY_RISK_PATTERN at WARN). See docs/relay-risk.md.
 */

/** How the card or phone was presented, as reported by the payment engine. */
public enum class EntryMode {
    /** A physical card tapped. */
    CONTACTLESS_CARD,

    /** A phone or watch wallet tapped. */
    CONTACTLESS_WALLET,

    /** Chip insert, swipe, keyed, or anything else that isn't a tap. */
    OTHER,

    /** The engine doesn't say. */
    UNKNOWN,
}

/**
 * Non-sensitive metadata about one authorized payment, filled in by your adapter
 * from whatever the payment engine exposes. **Never** put card numbers, EMV
 * tags, card fingerprints, or tokens here: the rules don't need them.
 */
public data class TapMetadata(
    val txnId: TxnId,
    val at: Instant,
    val amount: Money,
    val entryMode: EntryMode,
    /** How long the card read took, only if the engine reports it. */
    val cardReadMillis: Long? = null,
) {
    init {
        require(cardReadMillis == null || cardReadMillis >= 0) { "cardReadMillis must not be negative." }
    }

    internal val isContactless: Boolean
        get() = entryMode == EntryMode.CONTACTLESS_CARD || entryMode == EntryMode.CONTACTLESS_WALLET
}

/** One rule's finding. [explanation] is plain English for the merchant and must contain no data. */
public data class RuleHit(val ruleId: String, val explanation: String)

/**
 * A relay-risk rule. Implement this to plug in your own rules (for example,
 * tuned rules from a private product) without forking posguard.
 */
public interface RelayRiskRule {
    /** Stable identifier, for example `"relay.wallet_burst"`. Sent in telemetry. */
    public val id: String

    /**
     * @param current the payment being evaluated.
     * @param recent earlier payments still in the monitor's history, oldest first.
     *   [current] is not included.
     * @return a hit, or null when the rule doesn't fire.
     */
    public fun evaluate(current: TapMetadata, recent: List<TapMetadata>): RuleHit?
}

/** Result of [RelayRiskMonitor.record]. */
public sealed interface RelayRiskAssessment {
    public data object Clear : RelayRiskAssessment

    /** Hold the payment for review. Never decline on this alone. */
    public data class Flagged(val hits: List<RuleHit>) : RelayRiskAssessment
}

/** A payment waiting for the merchant's review. */
public data class FlaggedTap(val txnId: TxnId, val at: Instant, val hits: List<RuleHit>)

/** Taps in [recent] (plus [current]) that happened within [window] before or at [current]. */
internal fun inWindow(current: TapMetadata, recent: List<TapMetadata>, window: Duration): List<TapMetadata> =
    recent.filter { !it.at.isAfter(current.at) && java.time.Duration.between(it.at, current.at).toKotlinDuration() <= window } + current

/** Fires when at least [count] **wallet** taps happen within [window], counting the current one. */
public class WalletBurstRule(private val count: Int, private val window: Duration) : RelayRiskRule {
    init {
        require(count >= 2) { "count must be at least 2." }
        require(window.isPositive()) { "window must be positive." }
    }

    override val id: String = ID

    override fun evaluate(current: TapMetadata, recent: List<TapMetadata>): RuleHit? {
        if (current.entryMode != EntryMode.CONTACTLESS_WALLET) return null
        val taps = inWindow(current, recent, window).count { it.entryMode == EntryMode.CONTACTLESS_WALLET }
        if (taps < count) return null
        return RuleHit(id, "$taps phone-wallet taps within ${window.inWholeSeconds} seconds.")
    }

    public companion object {
        public const val ID: String = "relay.wallet_burst"
    }
}

/**
 * Fires when at least [count] contactless taps within [window] each fall just
 * under the no-CVM (no PIN or signature) limit: between [percentBelow]% below
 * [limit] and [limit] itself. Off by default because limits vary by country,
 * card network, and merchant category.
 */
public class NearCvmLimitBurstRule(
    private val limit: Money,
    private val percentBelow: Int,
    private val count: Int,
    private val window: Duration,
) : RelayRiskRule {
    init {
        require(limit.amountCents > 0) { "limit must be positive." }
        require(percentBelow in 1..50) { "percentBelow must be between 1 and 50." }
        require(count >= 2) { "count must be at least 2." }
        require(window.isPositive()) { "window must be positive." }
    }

    override val id: String = ID

    private val floorCents = limit.amountCents * (100 - percentBelow) / 100

    private fun nearLimit(tap: TapMetadata) = tap.isContactless &&
        tap.amount.currency == limit.currency &&
        tap.amount.amountCents in floorCents..limit.amountCents

    override fun evaluate(current: TapMetadata, recent: List<TapMetadata>): RuleHit? {
        if (!nearLimit(current)) return null
        val taps = inWindow(current, recent, window).count(::nearLimit)
        if (taps < count) return null
        return RuleHit(id, "$taps contactless payments just under the no-PIN limit within ${window.inWholeSeconds} seconds.")
    }

    public companion object {
        public const val ID: String = "relay.near_cvm_limit_burst"
    }
}

/**
 * Fires when the engine reports a card read that took at least [thresholdMillis].
 * Relaying adds delay, but app-level timing is weak evidence: slow phones,
 * worn cards, and busy engines are slow too. Off by default.
 */
public class SlowCardReadRule(private val thresholdMillis: Long) : RelayRiskRule {
    init {
        require(thresholdMillis > 0) { "thresholdMillis must be positive." }
    }

    override val id: String = ID

    override fun evaluate(current: TapMetadata, recent: List<TapMetadata>): RuleHit? {
        val millis = current.cardReadMillis ?: return null
        if (!current.isContactless || millis < thresholdMillis) return null
        return RuleHit(id, "The card read took unusually long ($millis ms).")
    }

    public companion object {
        public const val ID: String = "relay.slow_card_read"
    }
}
