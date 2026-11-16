package dev.posguard.assess.rules

import dev.posguard.assess.hits
import dev.posguard.assess.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRulesTest {

    @Test
    fun `identifier words split camel, snake, and constants`() {
        assertEquals(listOf("card", "number"), Heuristics.words("cardNumber"))
        assertEquals(listOf("pref", "last", "txn"), Heuristics.words("PREF_LAST_TXN"))
        assertEquals(listOf("track2", "data"), Heuristics.words("track2Data"))
        assertEquals(listOf("emv", "tag"), Heuristics.words("EMVTag"))
    }

    @Test
    fun `card data names`() {
        listOf("pan", "cardNumber", "card_no", "track2", "TRACK_DATA", "cvv", "pinBlock", "pin", "accountNumber").forEach {
            assertTrue(it, Heuristics.isCardDataName(it))
        }
        listOf("panel", "spinner", "cardholderName", "trackEvent", "pinned", "company", "span").forEach {
            assertFalse(it, Heuristics.isCardDataName(it))
        }
    }

    @Test
    fun `flags log calls that reference card data variables`() {
        val file = source(
            "Pay.kt",
            """
            Log.d(TAG, "read " + pan)
            Timber.i("card ${'$'}cardNumber read")
            println(track2Data)
            logger.debug("cvv={}", cvv)
            System.out.println(pinBlock);
            Timber.tag("pay").w(card.number + cardNumber)
            """,
        )
        val hits = CardDataInLogs.hits(file)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), hits.map { it.line })
        assertEquals("Logging call references `pan`.", hits[0].detail)
    }

    @Test
    fun `ignores card words inside string text, comments, and non-log calls`() {
        val file = source(
            "Pay.kt",
            """
            Log.d(TAG, "Enter PIN on the card reader")
            Log.i(TAG, "txn " + txnId)
            // Log.d(TAG, pan)
            show(pan)
            val panel = Panel()
            """,
        )
        assertEquals(emptyList<Any>(), CardDataInLogs.hits(file))
    }

    @Test
    fun `never quotes logged values in the detail`() {
        val file = source("Pay.kt", "Log.d(TAG, \"4111111111111111 \" + pan)")
        val detail = CardDataInLogs.hits(file).single().detail
        assertFalse(detail.contains("4111"))
    }

    @Test
    fun `flags payment state written to plain preferences and files`() {
        val file = source(
            "Store.java",
            """
            prefs.edit().putString("pending_txn", json).apply();
            editor.putLong(Keys.LAST_PAYMENT_AMOUNT, amount);
            editor.putString("card_number", number);
            FileOutputStream out = openFileOutput("payments.dat", MODE_PRIVATE);
            """,
        )
        val hits = PaymentStatePlainPrefs.hits(file)
        assertEquals(listOf(1, 2, 3, 4), hits.map { it.line })
        assertEquals("Writes a card-data-like key to SharedPreferences.", hits[2].detail)
        assertTrue(hits.none { it.detail.contains("pending_txn") })
    }

    @Test
    fun `ignores unrelated keys and encrypted storage`() {
        val unrelated = source("Settings.kt", "prefs.edit().putBoolean(\"dark_mode\", on).putString(KEY_THEME, t).putString(\"auth_token\", t)")
        assertEquals(emptyList<Any>(), PaymentStatePlainPrefs.hits(unrelated))
        val chained = source("Settings.kt", "prefs.edit().putBoolean(\"dark_mode\", on).putString(\"last_txn\", t)")
        assertEquals(1, PaymentStatePlainPrefs.hits(chained).size)
        val encrypted = source("Store.kt", "val p = EncryptedSharedPreferences.create(...)\np.edit().putString(\"pending_txn\", j)")
        assertEquals(emptyList<Any>(), PaymentStatePlainPrefs.hits(encrypted))
    }
}
