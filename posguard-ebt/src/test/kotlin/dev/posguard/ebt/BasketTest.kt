package dev.posguard.ebt

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BasketTest {

    private val rules = EligibilityRules(
        categories = mapOf("produce" to Eligibility.ELIGIBLE, "soda" to Eligibility.ELIGIBLE, "household" to Eligibility.INELIGIBLE),
        restrictedCategories = setOf("soda"),
    )

    private fun line(id: String, category: String, cents: Long, qty: Int = 1) =
        BasketLine(id, category, Money(cents, "USD"), qty)

    @Test
    fun `splits eligible, restricted, ineligible, and unknown lines with quantities`() {
        val split = BasketSplitter.split(
            listOf(
                line("bananas", "produce", 199, qty = 3),
                line("soda", "soda", 150),
                line("soap", "household", 425),
                line("gizmo", "mystery", 100),
            ),
            rules,
        )
        assertEquals(597L, split.snapEligibleCents)
        assertEquals(675L, split.otherCents)
        assertEquals(1272L, split.totalCents)
        assertEquals(
            listOf(LineReason.ELIGIBLE, LineReason.RESTRICTED_IN_JURISDICTION, LineReason.INELIGIBLE_CATEGORY, LineReason.UNKNOWN_CATEGORY),
            split.lines.map { it.reason },
        )
    }

    @Test
    fun `rejects empty baskets, mixed currencies, bad quantities, and overflow`() {
        assertThrows(IllegalArgumentException::class.java) { BasketSplitter.split(emptyList(), rules) }
        assertThrows(IllegalArgumentException::class.java) {
            BasketSplitter.split(listOf(line("a", "produce", 100), BasketLine("b", "produce", Money(100, "CAD"), 1)), rules)
        }
        assertThrows(IllegalArgumentException::class.java) { line("a", "produce", 100, qty = 0) }
        assertThrows(ArithmeticException::class.java) {
            BasketSplitter.split(listOf(line("a", "produce", Long.MAX_VALUE / 2, qty = 3)), rules)
        }
    }

    @Test
    fun `plan puts eligible on SNAP and the rest on the remainder tender`() {
        val split = BasketSplitter.split(listOf(line("bananas", "produce", 600), line("soap", "household", 400)), rules)
        assertEquals(
            listOf(TenderLeg(Tender.EBT_SNAP, Money(600, "USD")), TenderLeg(Tender.CARD, Money(400, "USD"))),
            EbtTenderPlanner.plan(split),
        )
        assertEquals(Tender.EBT_CASH, EbtTenderPlanner.plan(split, Tender.EBT_CASH)[1].tender)
        assertThrows(IllegalArgumentException::class.java) { EbtTenderPlanner.plan(split, Tender.EBT_SNAP) }
    }

    @Test
    fun `plan skips empty legs`() {
        val snapOnly = BasketSplitter.split(listOf(line("bananas", "produce", 600)), rules)
        assertEquals(listOf(Tender.EBT_SNAP), EbtTenderPlanner.plan(snapOnly).map { it.tender })
        val otherOnly = BasketSplitter.split(listOf(line("soap", "household", 400)), rules)
        assertEquals(listOf(Tender.CARD), EbtTenderPlanner.plan(otherOnly).map { it.tender })
    }

    @Test
    fun `partial approval moves the shortfall to the remainder`() {
        val snap = TenderLeg(Tender.EBT_SNAP, Money(600, "USD"))
        val card = TenderLeg(Tender.CARD, Money(400, "USD"))

        assertEquals(listOf(card), EbtTenderPlanner.afterSnapApproval(snap, Money(600, "USD"), listOf(card)))
        assertEquals(
            listOf(TenderLeg(Tender.CARD, Money(650, "USD"))),
            EbtTenderPlanner.afterSnapApproval(snap, Money(350, "USD"), listOf(card)),
        )
        // SNAP-only basket: the shortfall becomes a new leg.
        assertEquals(
            listOf(TenderLeg(Tender.EBT_CASH, Money(100, "USD"))),
            EbtTenderPlanner.afterSnapApproval(snap, Money(500, "USD"), emptyList(), Tender.EBT_CASH),
        )
        assertThrows(IllegalArgumentException::class.java) {
            EbtTenderPlanner.afterSnapApproval(snap, Money(700, "USD"), listOf(card))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EbtTenderPlanner.afterSnapApproval(card, Money(100, "USD"), emptyList())
        }
    }
}
