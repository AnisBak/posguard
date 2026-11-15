package dev.posguard.ebt

import dev.posguard.core.ledger.EngineStatusSource
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender

/**
 * Adapter around a **certified** EBT-capable payment engine. The integrator
 * implements it. `ebt-simulator` implements it for demos and tests.
 *
 * The engine reads the card, collects and encrypts the PIN on its own secure
 * PIN entry, and talks to the processor. posguard only sees the outcomes
 * below. Nothing here carries card numbers, track data, or PINs.
 */
public interface EbtEngine : EngineStatusSource {

    /**
     * Asks the customer to present the card in [mode] and reads it, including
     * PIN entry on the engine's secure PIN entry. Nothing is sent to the
     * processor yet.
     */
    public suspend fun readCard(mode: EbtEntryMode): CardRead

    /** Sends a purchase for a card read by [readCard]. */
    public suspend fun purchase(request: EbtPurchaseRequest): EbtPurchaseResult
}

/** Result of [EbtEngine.readCard]. */
public sealed interface CardRead {
    /**
     * The card was read and the PIN collected by the engine. [handle] is an
     * opaque engine token for this read, never card data.
     */
    public data class Ready(val handle: String, val mode: EbtEntryMode) : CardRead

    public data class Failed(val failure: EntryFailure) : CardRead
}

public data class EbtPurchaseRequest(
    val amount: Money,
    /** [Tender.EBT_SNAP] or [Tender.EBT_CASH]. */
    val tender: Tender,
    val idempotencyKey: IdempotencyKey,
    val read: CardRead.Ready,
) {
    init {
        require(tender == Tender.EBT_SNAP || tender == Tender.EBT_CASH) { "EBT purchases are SNAP or cash only." }
    }
}

/**
 * Remaining benefit balances returned with an approval. For the **customer
 * receipt only**: the proposed rule (7 CFR 274.8(b)(6)(i)) says balance
 * information shall not be displayed on the POS terminal screen. Never logged
 * or sent to telemetry.
 */
public data class EbtBalance(val currency: String, val snapCents: Long?, val cashCents: Long?) {
    init {
        require((snapCents ?: 0) >= 0 && (cashCents ?: 0) >= 0) { "Balances can't be negative." }
    }

    override fun toString(): String = "EbtBalance(redacted)"
}

/** Result of [EbtEngine.purchase]. */
public sealed interface EbtPurchaseResult {
    /** Approved for [approvedAmount], which may be less than requested (partial approval). */
    public data class Approved(
        val engineReference: String,
        val approvedAmount: Money,
        val balance: EbtBalance? = null,
    ) : EbtPurchaseResult

    public data class Declined(val engineReference: String? = null, val declineCode: String? = null) : EbtPurchaseResult

    /** No answer (timeout, connection lost after submit). Resolve through ledger recovery. */
    public data object Unknown : EbtPurchaseResult
}
