package dev.posguard.core.event

import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.verdict.GuardVerdict
import java.time.Instant

/**
 * Events posguard emits for telemetry and audit. They carry no card data. Sinks
 * still run them through the telemetry scrubber.
 *
 * New subtypes may be added in minor versions, so keep an `else` branch when
 * you `when` over this type.
 */
public sealed interface GuardEvent {
    public val at: Instant

    /** A preflight finished. */
    public data class PreflightEvaluated(val verdict: GuardVerdict) : GuardEvent {
        override val at: Instant get() = verdict.evaluatedAt
    }

    /** A ledger transaction changed state. [from] is null when the transaction is created. */
    public data class TxnStateChanged(
        val id: TxnId,
        val from: TxnState?,
        val to: TxnState,
        override val at: Instant,
    ) : GuardEvent

    /**
     * Experimental relay-risk rules flagged an authorized payment for review.
     * [ruleIds] are rule identifiers such as `"relay.wallet_burst"`. This is
     * never a decline.
     */
    public data class RelayRiskFlagged(
        val id: TxnId,
        val ruleIds: List<String>,
        override val at: Instant,
    ) : GuardEvent

    /** The merchant reviewed a payment flagged by [RelayRiskFlagged]. */
    public data class RelayRiskReviewed(
        val id: TxnId,
        override val at: Instant,
    ) : GuardEvent

    /**
     * An EBT card could not be read in mode [from], so checkout moved to mode
     * [to], or gave up when [to] is null. Modes and [reason] are names such as
     * `"CHIP_INSERT"` and `"CHIP_READ_FAILED"`. No card data and no amounts.
     */
    public data class EbtEntryFallback(
        val id: TxnId,
        val from: String,
        val to: String?,
        val reason: String,
        override val at: Instant,
    ) : GuardEvent

    /** An EBT leg was approved for less than requested. Amounts are deliberately left out. */
    public data class EbtPartialApproval(
        val id: TxnId,
        override val at: Instant,
    ) : GuardEvent
}

/**
 * Receives [GuardEvent]s. This is the plug-in point for telemetry modules and
 * external dashboards. Called synchronously on a background thread, so keep it
 * fast and hand off heavy work. Exceptions thrown here are caught and
 * ignored, so a listener can never break checkout.
 */
public fun interface GuardEventListener {
    public fun onEvent(event: GuardEvent)
}
