package dev.posguard.telemetry

import dev.posguard.telemetry.ScrubRules.EMAIL_PLACEHOLDER
import dev.posguard.telemetry.ScrubRules.NUMBER_PLACEHOLDER
import dev.posguard.telemetry.ScrubRules.PAN_PLACEHOLDER
import dev.posguard.telemetry.ScrubRules.PHONE_PLACEHOLDER
import dev.posguard.telemetry.ScrubRules.TRACK_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrubber is the last line of defense against card data in telemetry.
 * Only publicly documented **test** card numbers are used here.
 */
class ScrubberTest {

    private val scrubber = Scrubber.Default
    private fun scrub(s: String) = scrubber.scrub(s)

    /** Publicly documented network test PANs (13–19 digits). */
    private val testPans = listOf(
        "4111111111111111", // Visa
        "4242424242424242", // Visa
        "4222222222222", // Visa, 13 digits
        "5555555555554444", // Mastercard
        "2223003122003222", // Mastercard 2-series
        "378282246310005", // Amex, 15 digits
        "6011111111111117", // Discover
        "3530111333300000", // JCB
        "30569309025904", // Diners, 14 digits
    )

    /** Appends a Luhn check digit so [prefix] becomes a valid number. */
    private fun withCheckDigit(prefix: String): String =
        (0..9).map { prefix + it }.first { ScrubRules.luhnValid(it) }

    // --- Luhn ---------------------------------------------------------------------------

    @Test
    fun `luhn accepts documented test PANs`() {
        testPans.forEach { assertTrue(it, ScrubRules.luhnValid(it)) }
    }

    @Test
    fun `luhn rejects altered numbers and non digits`() {
        assertFalse(ScrubRules.luhnValid("4111111111111112"))
        assertFalse(ScrubRules.luhnValid(""))
        assertFalse(ScrubRules.luhnValid("4111-1111"))
    }

    // --- Card numbers -------------------------------------------------------------------

    @Test
    fun `plain test PANs are redacted`() {
        testPans.forEach { assertEquals(it, PAN_PLACEHOLDER, scrub(it)) }
    }

    @Test
    fun `19 digit Luhn valid number is redacted`() {
        val pan19 = withCheckDigit("411111111111111111")
        assertEquals(19, pan19.length)
        assertEquals(PAN_PLACEHOLDER, scrub(pan19))
    }

    @Test
    fun `spaced and dashed PANs are redacted`() {
        assertEquals(PAN_PLACEHOLDER, scrub("4111 1111 1111 1111"))
        assertEquals(PAN_PLACEHOLDER, scrub("4111-1111-1111-1111"))
        assertEquals(PAN_PLACEHOLDER, scrub("3782 822463 10005"))
        assertEquals(PAN_PLACEHOLDER, scrub("5555-5555 5555-4444"))
    }

    @Test
    fun `PAN embedded in text is redacted and the rest is kept`() {
        assertEquals(
            "Payment failed for card $PAN_PLACEHOLDER at lane 3",
            scrub("Payment failed for card 4111111111111111 at lane 3"),
        )
    }

    @Test
    fun `PAN inside JSON-like text is redacted`() {
        assertEquals("""{"pan":"$PAN_PLACEHOLDER"}""", scrub("""{"pan":"4111111111111111"}"""))
        assertEquals("card=$PAN_PLACEHOLDER&x=1", scrub("card=4111111111111111&x=1"))
    }

    @Test
    fun `every occurrence is redacted`() {
        assertEquals(
            "$PAN_PLACEHOLDER then $PAN_PLACEHOLDER",
            scrub("4111111111111111 then 5555555555554444"),
        )
    }

    @Test
    fun `non Luhn 13 to 19 digit runs are still redacted`() {
        assertEquals(NUMBER_PLACEHOLDER, scrub("4111111111111112")) // one digit off
        assertEquals(NUMBER_PLACEHOLDER, scrub("1234567890123"))
        assertEquals(NUMBER_PLACEHOLDER, scrub("1234 5678 9012 3456 789"))
    }

    @Test
    fun `epoch millis is a documented false positive`() {
        assertEquals("at $NUMBER_PLACEHOLDER", scrub("at 1731585600000"))
    }

    @Test
    fun `short and very long contiguous digit runs are left alone`() {
        assertEquals("order 123456789012", scrub("order 123456789012")) // 12 digits
        assertEquals("ref 12345678901234567890", scrub("ref 12345678901234567890")) // 20 digits
    }

    // --- Track data ---------------------------------------------------------------------

    @Test
    fun `track 2 data is redacted entirely`() {
        assertEquals("read $TRACK_PLACEHOLDER done", scrub("read ;4111111111111111=25121010000000000000? done"))
        assertEquals(TRACK_PLACEHOLDER, scrub("4111111111111111=2512101"))
    }

    @Test
    fun `track 1 data with cardholder name is redacted entirely`() {
        assertEquals(
            "x $TRACK_PLACEHOLDER y",
            scrub("x %B4111111111111111^DOE/JANE^2512101000000000000? y"),
        )
    }

    // --- Email --------------------------------------------------------------------------

    @Test
    fun `emails are redacted`() {
        assertEquals("contact $EMAIL_PLACEHOLDER now", scrub("contact jane.doe+pos@example.co.uk now"))
        assertEquals(EMAIL_PLACEHOLDER, scrub("a_b-c%d@sub.example.com"))
    }

    // --- Phone --------------------------------------------------------------------------

    @Test
    fun `north american phone formats are redacted`() {
        listOf("(415) 555-0123", "415-555-0123", "415.555.0123", "415 555 0123", "4155550123").forEach {
            assertEquals(it, "call $PHONE_PLACEHOLDER", scrub("call $it"))
        }
    }

    @Test
    fun `international phone numbers are redacted`() {
        assertEquals(PHONE_PLACEHOLDER, scrub("+1 415 555 0123"))
        assertEquals(PHONE_PLACEHOLDER, scrub("+44 20 7946 0958"))
        assertEquals(PHONE_PLACEHOLDER, scrub("+33 1 23 45 67 89"))
    }

    // --- Things that must survive -------------------------------------------------------

    @Test
    fun `operational values are not redacted`() {
        val safe = listOf(
            "550e8400-e29b-41d4-a716-446655440000",
            "12345678-1234-1234-1234-123456789012", // all-digit UUID
            "Total 12.50 USD",
            "2026-11-14T12:00:00Z",
            "SCREEN_OVERLAY_PRESENT",
            "Android 15 (API 35)",
            "versionName 0.1.0",
            "lane 3 store 042",
            "MOCK-1A2B3C4D",
        )
        safe.forEach { assertEquals(it, it, scrub(it)) }
    }

    // --- Attributes and sensitive keys --------------------------------------------------

    @Test
    fun `sensitive keys have their values replaced`() {
        val out = scrubber.scrubAttributes(
            mapOf(
                "cardNumber" to "anything",
                "card_last4" to "1111",
                "CVV" to "123",
                "pinBlock" to "x",
                "customer-email" to "nobody",
                "api_key" to "k",
                "authToken" to "t",
                "lane" to "3",
            ),
        )
        assertEquals("3", out["lane"])
        (out - "lane").values.forEach { assertEquals(Scrubber.REDACTED, it) }
    }

    @Test
    fun `token matching avoids substring false positives`() {
        assertFalse(scrubber.isSensitiveKey("shipping"))
        assertFalse(scrubber.isSensitiveKey("spinnerState"))
        assertFalse(scrubber.isSensitiveKey("panelName"))
        assertTrue(scrubber.isSensitiveKey("PAN"))
        assertTrue(scrubber.isSensitiveKey("TRACK2"))
    }

    @Test
    fun `values and keys of non sensitive attributes are scrubbed too`() {
        val out = scrubber.scrubAttributes(
            mapOf(
                "note" to "customer said 4111 1111 1111 1111",
                "4111111111111111" to "weird key",
            ),
        )
        assertEquals("customer said $PAN_PLACEHOLDER", out["note"])
        assertEquals("weird key", out[PAN_PLACEHOLDER])
    }

    @Test
    fun `extra sensitive keys can be added`() {
        val custom = Scrubber(extraSensitiveKeys = setOf("loyaltyId"))
        assertTrue(custom.isSensitiveKey("loyaltyId"))
        assertTrue(custom.isSensitiveKey("loyalty_id"))
        assertEquals(Scrubber.REDACTED, custom.scrubAttributes(mapOf("loyalty_id" to "L-9"))["loyalty_id"])
    }

    // --- Custom rules and failure -------------------------------------------------------

    @Test
    fun `custom rules run after defaults`() {
        val custom = Scrubber(ScrubRules.DEFAULT + ScrubRule { it.replace("SECRETCODE", "[X]") })
        assertEquals("[X] $PAN_PLACEHOLDER", custom.scrub("SECRETCODE 4111111111111111"))
    }

    @Test
    fun `a throwing rule fails closed`() {
        val broken = Scrubber(listOf(ScrubRule { error("boom 4111111111111111") }))
        assertEquals(Scrubber.SCRUB_ERROR, broken.scrub("4111111111111111"))
        assertEquals(
            mapOf(Scrubber.SCRUB_ERROR to Scrubber.SCRUB_ERROR),
            broken.scrubAttributes(mapOf("note" to "x")),
        )
    }

    @Test
    fun `PAN next to a UUID is still redacted`() {
        assertEquals(
            "txn 550e8400-e29b-41d4-a716-446655440000 card $PAN_PLACEHOLDER",
            scrub("txn 550e8400-e29b-41d4-a716-446655440000 card 4111111111111111"),
        )
    }

    @Test
    fun `no test PAN survives any default scrub`() {
        val corpus = testPans.flatMap { pan ->
            listOf(pan, pan.chunked(4).joinToString(" "), pan.chunked(4).joinToString("-"), "x${pan}y", "[$pan]")
        }
        corpus.forEach { input ->
            val out = scrub(input)
            testPans.forEach { pan -> assertFalse("$input -> $out", out.filter(Char::isDigit).contains(pan)) }
        }
    }
}
