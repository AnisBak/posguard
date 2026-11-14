package dev.posguard.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ValuesTest {

    @Test
    fun `money accepts positive amount and ISO currency`() {
        val money = Money(1250, "USD")
        assertEquals(1250L, money.amountCents)
        assertEquals("USD", money.currency)
    }

    @Test
    fun `money rejects zero and negative amounts`() {
        assertThrows(IllegalArgumentException::class.java) { Money(0, "USD") }
        assertThrows(IllegalArgumentException::class.java) { Money(-1, "USD") }
    }

    @Test
    fun `money rejects malformed currency codes`() {
        listOf("usd", "US", "USDX", "", "U5D", " USD").forEach { code ->
            assertThrows("currency '$code'", IllegalArgumentException::class.java) { Money(100, code) }
        }
    }

    @Test
    fun `ids reject blank values`() {
        assertThrows(IllegalArgumentException::class.java) { TxnId("") }
        assertThrows(IllegalArgumentException::class.java) { TxnId("   ") }
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKey("") }
    }

    @Test
    fun `ids print their raw value`() {
        assertEquals("txn-1", TxnId("txn-1").toString())
        assertEquals("key-1", IdempotencyKey("key-1").toString())
    }
}
