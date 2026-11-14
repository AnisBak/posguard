package dev.posguard.core.ledger

/** Ledger-assigned transaction identifier. Opaque. Never derived from card data. */
@JvmInline
public value class TxnId(public val value: String) {
    init {
        require(value.isNotBlank()) { "TxnId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Key sent with every submission and status query so the engine can
 * deduplicate. Created once in [TransactionLedger.begin] and never changed:
 * a retry must reuse the same key.
 */
@JvmInline
public value class IdempotencyKey(public val value: String) {
    init {
        require(value.isNotBlank()) { "IdempotencyKey must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * A positive amount in minor units (cents) of an ISO 4217 currency.
 * Only the format is checked (three uppercase letters). Currency support is
 * the payment engine's job.
 */
public data class Money(val amountCents: Long, val currency: String) {
    init {
        require(amountCents > 0) { "amountCents must be positive, was $amountCents." }
        require(CURRENCY_FORMAT.matches(currency)) {
            "currency must be an ISO 4217 code like \"USD\", was \"$currency\"."
        }
    }

    private companion object {
        val CURRENCY_FORMAT = Regex("[A-Z]{3}")
    }
}

/**
 * Tender type. Adding a constant is a minor-version change, so consumers using
 * exhaustive `when` should keep an `else` branch.
 */
public enum class Tender {
    /** Credit or debit card, contact or contactless, including phone wallets. */
    CARD,

    /** EBT SNAP (food) benefits. EBT flows are Milestone 3 and simulator only. */
    EBT_SNAP,

    /** EBT cash benefits. EBT flows are Milestone 3 and simulator only. */
    EBT_CASH,
}
