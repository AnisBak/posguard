package dev.posguard.ebt

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TxnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class EbtReceiptTest {

    private val merchant = MerchantInfo("Sunrise Market", "12 Example St, Springfield")
    private val at = Instant.parse("2026-11-15T15:30:00Z")

    @Test
    fun `receipt has the required fields, partial amounts, and the coded transaction number`() {
        val snap = EbtLegResult.Approved(
            TxnId("t1"), Tender.EBT_SNAP, Money(1000, "USD"), Money(600, "USD"), EbtEntryMode.CHIP_TAP, "SIM-42",
            EbtBalance("USD", 0, 250),
        )
        val receipt = EbtReceipt(
            merchant,
            at,
            listOf(EbtReceipt.leg(snap), EbtReceiptLeg(Tender.CARD, Money(400, "USD"), Money(400, "USD"), codedTransactionNumber = "MOCK-7")),
            snap.balance,
        )
        val text = receipt.lines(ZoneOffset.UTC).joinToString("\n")

        listOf(
            "Sunrise Market", "12 Example St, Springfield", "2026-11-15 15:30", "EBT SNAP PURCHASE",
            "Requested \$10.00", "Approved  \$6.00 (partial)", "Entry     Contactless", "Txn #     SIM-42",
            "CARD PURCHASE", "Amount    \$4.00", "SNAP balance  \$0.00", "Cash balance  \$2.50",
        ).forEach { assertTrue("missing: $it\n$text", text.contains(it)) }
    }

    @Test
    fun `screen version replaces balances with a note`() {
        val receipt = EbtReceipt(
            merchant, at,
            listOf(EbtReceiptLeg(Tender.EBT_SNAP, Money(100, "USD"), Money(100, "USD"))),
            EbtBalance("USD", 98765, 43210),
        )
        val screen = receipt.lines(ZoneOffset.UTC, includeBalance = false)
        assertTrue(screen.contains(EbtReceipt.BALANCE_ON_RECEIPT))
        assertFalse(screen.any { it.contains("987") || it.contains("432") })
    }

    @Test
    fun `toString never shows balances`() {
        val receipt = EbtReceipt(
            merchant, at,
            listOf(EbtReceiptLeg(Tender.EBT_SNAP, Money(100, "USD"), Money(100, "USD"))),
            EbtBalance("USD", 98765, 43210),
        )
        assertFalse(receipt.toString().contains("987"))
        assertFalse(receipt.balance.toString().contains("432"))
    }

    @Test
    fun `requires merchant details and a leg`() {
        assertThrows(IllegalArgumentException::class.java) { MerchantInfo("Sunrise Market", " ") }
        assertThrows(IllegalArgumentException::class.java) { EbtReceipt(merchant, at, emptyList()) }
        assertEquals("CAD 1.05", EbtReceipt.format(105, "CAD"))
    }
}
