package dev.posguard.integrity.relay

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.TxnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class RelayRiskRulesTest {

    private val t0 = Instant.parse("2026-11-15T12:00:00Z")
    private var n = 0

    private fun tap(
        secondsAfter: Long,
        mode: EntryMode = EntryMode.CONTACTLESS_WALLET,
        cents: Long = 500,
        currency: String = "USD",
        readMillis: Long? = null,
    ) = TapMetadata(TxnId("t-${++n}"), t0.plusSeconds(secondsAfter), Money(cents, currency), mode, readMillis)

    // --- wallet burst ---

    private val burst = WalletBurstRule(count = 3, window = 60.seconds)

    @Test
    fun `wallet burst fires at exactly the count`() {
        val hit = burst.evaluate(tap(30), listOf(tap(0), tap(10)))
        assertNotNull(hit)
        assertEquals(WalletBurstRule.ID, hit!!.ruleId)
        assertEquals("3 phone-wallet taps within 60 seconds.", hit.explanation)
    }

    @Test
    fun `wallet burst does not fire below the count`() {
        assertNull(burst.evaluate(tap(30), listOf(tap(10))))
    }

    @Test
    fun `wallet burst window is inclusive at the edge and excludes older taps`() {
        assertNotNull(burst.evaluate(tap(60), listOf(tap(0), tap(30))))
        assertNull(burst.evaluate(tap(61), listOf(tap(0), tap(30))))
    }

    @Test
    fun `wallet burst counts only wallet taps`() {
        val recent = listOf(tap(0, EntryMode.CONTACTLESS_CARD), tap(5, EntryMode.OTHER), tap(10))
        assertNull(burst.evaluate(tap(20), recent))
    }

    @Test
    fun `wallet burst ignores a current tap that is not a wallet`() {
        assertNull(burst.evaluate(tap(20, EntryMode.CONTACTLESS_CARD), listOf(tap(0), tap(10))))
    }

    @Test
    fun `taps recorded after the current one are ignored`() {
        assertNull(burst.evaluate(tap(10), listOf(tap(0), tap(50))))
    }

    // --- near no-CVM limit ---

    private val nearLimit = NearCvmLimitBurstRule(Money(10_000, "USD"), percentBelow = 10, count = 3, window = 60.seconds)

    @Test
    fun `near limit fires for taps between the floor and the limit`() {
        val recent = listOf(tap(0, cents = 9_000), tap(10, EntryMode.CONTACTLESS_CARD, cents = 9_999))
        val hit = nearLimit.evaluate(tap(20, cents = 10_000), recent)
        assertEquals(NearCvmLimitBurstRule.ID, hit?.ruleId)
    }

    @Test
    fun `near limit ignores amounts outside the band, other currencies, and non-contactless`() {
        val recent = listOf(
            tap(0, cents = 8_999),
            tap(5, cents = 10_001),
            tap(10, cents = 9_500, currency = "CAD"),
            tap(15, EntryMode.OTHER, cents = 9_500),
            tap(16, cents = 9_500),
        )
        assertNull(nearLimit.evaluate(tap(20, cents = 9_500), recent))
    }

    // --- slow card read ---

    private val slow = SlowCardReadRule(thresholdMillis = 1_500)

    @Test
    fun `slow read fires at the threshold`() {
        assertEquals(SlowCardReadRule.ID, slow.evaluate(tap(0, readMillis = 1_500), emptyList())?.ruleId)
        assertNull(slow.evaluate(tap(0, readMillis = 1_499), emptyList()))
    }

    @Test
    fun `slow read ignores missing timing and non-contactless`() {
        assertNull(slow.evaluate(tap(0, readMillis = null), emptyList()))
        assertNull(slow.evaluate(tap(0, EntryMode.OTHER, readMillis = 5_000), emptyList()))
    }

    // --- validation ---

    @Test
    fun `rules reject invalid settings`() {
        assertThrows(IllegalArgumentException::class.java) { WalletBurstRule(1, 60.seconds) }
        assertThrows(IllegalArgumentException::class.java) { WalletBurstRule(3, 0.seconds) }
        assertThrows(IllegalArgumentException::class.java) { NearCvmLimitBurstRule(Money(100, "USD"), 0, 3, 60.seconds) }
        assertThrows(IllegalArgumentException::class.java) { NearCvmLimitBurstRule(Money(100, "USD"), 51, 3, 60.seconds) }
        assertThrows(IllegalArgumentException::class.java) { SlowCardReadRule(0) }
        assertThrows(IllegalArgumentException::class.java) { tap(0, readMillis = -1) }
    }
}
