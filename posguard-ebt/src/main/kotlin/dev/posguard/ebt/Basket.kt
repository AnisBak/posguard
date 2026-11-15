package dev.posguard.ebt

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender

/** One basket line. Item data only: nothing about the customer or their card. */
public data class BasketLine(
    val itemId: String,
    /** Category id looked up in [EligibilityRules]. */
    val category: String,
    val unitPrice: Money,
    val quantity: Int,
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank." }
        require(quantity > 0) { "quantity must be positive, was $quantity." }
    }

    public val totalCents: Long get() = Math.multiplyExact(unitPrice.amountCents, quantity.toLong())
}

/** Why a line is or isn't SNAP-eligible. */
public enum class LineReason(public val snapEligible: Boolean, public val explanation: String) {
    ELIGIBLE(true, "SNAP-eligible"),
    INELIGIBLE_CATEGORY(false, "Not SNAP-eligible"),
    RESTRICTED_IN_JURISDICTION(false, "Restricted for SNAP in this jurisdiction"),
    UNKNOWN_CATEGORY(false, "Category not in the eligibility list, so treated as not eligible"),
}

public data class SplitLine(val line: BasketLine, val reason: LineReason) {
    public val snapEligible: Boolean get() = reason.snapEligible
}

/**
 * A basket split into the part SNAP may pay for and the rest. Totals are
 * plain cents because either may be zero.
 */
public data class BasketSplit(
    val lines: List<SplitLine>,
    val currency: String,
    val snapEligibleCents: Long,
    val otherCents: Long,
) {
    public val totalCents: Long get() = snapEligibleCents + otherCents
}

/** Splits a basket by [EligibilityRules]. Pure and deterministic. */
public object BasketSplitter {
    /**
     * @throws IllegalArgumentException if [lines] is empty or mixes currencies.
     * @throws ArithmeticException if a total overflows.
     */
    public fun split(lines: List<BasketLine>, rules: EligibilityRules): BasketSplit {
        require(lines.isNotEmpty()) { "Basket must not be empty." }
        val currency = lines.first().unitPrice.currency
        require(lines.all { it.unitPrice.currency == currency }) { "Basket lines must share one currency." }

        val split = lines.map { SplitLine(it, rules.decide(it.category)) }
        var eligible = 0L
        var other = 0L
        for (s in split) {
            if (s.snapEligible) eligible = Math.addExact(eligible, s.line.totalCents)
            else other = Math.addExact(other, s.line.totalCents)
        }
        return BasketSplit(split, currency, eligible, other)
    }
}

/** One payment in a split-tender sale. */
public data class TenderLeg(val tender: Tender, val amount: Money)

/**
 * Plans the legs of a split-tender sale: SNAP pays the eligible part, and
 * the rest goes to [remainderTender] (EBT cash, card, or another tender the
 * app handles).
 *
 * There is no balance lookup up front. If SNAP approves less than asked (a
 * partial approval), [afterSnapApproval] moves the shortfall to the remainder.
 */
public object EbtTenderPlanner {

    public fun plan(split: BasketSplit, remainderTender: Tender = Tender.CARD): List<TenderLeg> {
        require(remainderTender != Tender.EBT_SNAP) { "The remainder can't go to SNAP: it isn't SNAP-eligible." }
        return buildList {
            if (split.snapEligibleCents > 0) add(TenderLeg(Tender.EBT_SNAP, Money(split.snapEligibleCents, split.currency)))
            if (split.otherCents > 0) add(TenderLeg(remainderTender, Money(split.otherCents, split.currency)))
        }
    }

    /**
     * The legs still to pay after the SNAP leg was approved for [approved]
     * (the full amount or less).
     *
     * @param remaining the legs after the SNAP leg, from [plan].
     */
    public fun afterSnapApproval(
        snapLeg: TenderLeg,
        approved: Money,
        remaining: List<TenderLeg>,
        remainderTender: Tender = remaining.firstOrNull()?.tender ?: Tender.CARD,
    ): List<TenderLeg> {
        require(snapLeg.tender == Tender.EBT_SNAP) { "snapLeg must be the EBT_SNAP leg." }
        require(approved.currency == snapLeg.amount.currency) { "Currency mismatch." }
        require(approved.amountCents <= snapLeg.amount.amountCents) { "Approved more than requested." }
        require(remainderTender != Tender.EBT_SNAP) { "The shortfall can't go back to SNAP." }
        val shortfall = snapLeg.amount.amountCents - approved.amountCents
        if (shortfall == 0L) return remaining

        val currency = approved.currency
        val existing = remaining.firstOrNull { it.tender == remainderTender }
        return if (existing == null) {
            remaining + TenderLeg(remainderTender, Money(shortfall, currency))
        } else {
            remaining.map {
                if (it === existing) it.copy(amount = Money(Math.addExact(it.amount.amountCents, shortfall), currency)) else it
            }
        }
    }
}
