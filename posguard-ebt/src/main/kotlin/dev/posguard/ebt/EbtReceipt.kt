package dev.posguard.ebt

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

public data class MerchantInfo(val name: String, val location: String) {
    init {
        require(name.isNotBlank() && location.isNotBlank()) { "Merchant name and location are required on EBT receipts." }
    }
}

/** One payment on the receipt. */
public data class EbtReceiptLeg(
    val tender: Tender,
    val requested: Money,
    val approved: Money,
    val entryMode: EbtEntryMode? = null,
    /**
     * The engine reference, printed as the "coded transaction number". The
     * proposed rule allows that in place of a truncated card number
     * (7 CFR 274.8(b)(7), as printed in 91 FR 66958), so posguard never needs
     * card digits.
     */
    val codedTransactionNumber: String? = null,
    val transactionType: String = "PURCHASE",
)

/**
 * Customer receipt fields for an EBT sale. It covers the minimum contents in
 * the proposed rule's 7 CFR 274.8(b)(7): date, merchant name and location,
 * transaction type, transaction amount, and remaining SNAP balance. Check the
 * final rule and your state's EBT processor spec before relying on it.
 *
 * The balance appears here and **not on the POS screen**. Keep it out of
 * logs and telemetry: [toString] redacts it.
 */
public data class EbtReceipt(
    val merchant: MerchantInfo,
    val at: Instant,
    val legs: List<EbtReceiptLeg>,
    val balance: EbtBalance? = null,
) {
    init {
        require(legs.isNotEmpty()) { "A receipt needs at least one leg." }
    }

    /** Plain-text receipt lines for a printer or an electronic receipt. */
    public fun lines(zone: ZoneId): List<String> = buildList {
        add(merchant.name)
        add(merchant.location)
        add(DATE.withZone(zone).format(at))
        for (leg in legs) {
            add("")
            add("${tenderLabel(leg.tender)} ${leg.transactionType}")
            if (leg.approved != leg.requested) {
                add("  Requested ${format(leg.requested)}")
                add("  Approved  ${format(leg.approved)} (partial)")
            } else {
                add("  Amount    ${format(leg.approved)}")
            }
            leg.entryMode?.let { add("  Entry     ${entryLabel(it)}") }
            leg.codedTransactionNumber?.let { add("  Txn #     $it") }
        }
        balance?.let { b ->
            add("")
            b.snapCents?.let { add("SNAP balance  ${format(it, b.currency)}") }
            b.cashCents?.let { add("Cash balance  ${format(it, b.currency)}") }
        }
    }

    override fun toString(): String = "EbtReceipt(merchant=${merchant.name}, legs=${legs.size}, balance=redacted)"

    public companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

        /** Builds a receipt leg from an approved EBT leg. */
        public fun leg(result: EbtLegResult.Approved): EbtReceiptLeg = EbtReceiptLeg(
            tender = result.tender,
            requested = result.requested,
            approved = result.approved,
            entryMode = result.entryMode,
            codedTransactionNumber = result.engineReference,
        )

        private fun tenderLabel(t: Tender): String = when (t) {
            Tender.EBT_SNAP -> "EBT SNAP"
            Tender.EBT_CASH -> "EBT CASH"
            Tender.CARD -> "CARD"
        }

        private fun entryLabel(m: EbtEntryMode): String = when (m) {
            EbtEntryMode.CHIP_INSERT -> "Chip"
            EbtEntryMode.CHIP_TAP -> "Contactless"
            EbtEntryMode.MAGSTRIPE -> "Swipe (fallback)"
        }

        private fun format(m: Money): String = format(m.amountCents, m.currency)

        internal fun format(cents: Long, currency: String): String {
            val body = String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100)
            return if (currency == "USD") "$$body" else "$currency $body"
        }
    }
}
