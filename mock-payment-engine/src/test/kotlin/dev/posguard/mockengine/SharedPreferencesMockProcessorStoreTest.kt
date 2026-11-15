package dev.posguard.mockengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesMockProcessorStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `round trips approved and declined charges`() {
        val store = SharedPreferencesMockProcessorStore(context, "store-roundtrip")
        val approved = ProcessedCharge("k1", true, "MOCK-1", 100, "USD")
        val declined = ProcessedCharge("k2", false, "MOCK-2", 250, "USD", declineCode = "05")

        store.put(approved)
        store.put(declined)

        assertEquals(approved, store.get("k1"))
        assertEquals(declined, store.get("k2"))
        assertEquals(setOf(approved, declined), store.all().toSet())
        assertNull(store.get("missing"))
    }

    @Test
    fun `clear removes everything`() {
        val store = SharedPreferencesMockProcessorStore(context, "store-clear")
        store.put(ProcessedCharge("k1", true, "MOCK-1", 100, "USD"))
        store.clear()
        assertEquals(emptyList<ProcessedCharge>(), store.all())
    }

    @Test
    fun `processor memory survives a new engine instance`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = IdempotencyKey("persisted-key")
        val first = MockPaymentEngine.Builder()
            .store(SharedPreferencesMockProcessorStore(context, "store-persist"))
            .dispatcher(dispatcher)
            .build()
        first.charge(ChargeRequest(Money(999, "USD"), Tender.CARD, key))

        // "Relaunch": a new engine and store over the same preferences file.
        val second = MockPaymentEngine.Builder()
            .store(SharedPreferencesMockProcessorStore(context, "store-persist"))
            .dispatcher(dispatcher)
            .build()

        assertTrue(second.queryStatus(key) is EngineStatus.Authorized)
        assertEquals(1, second.processedCharges().size)
    }

    @Test
    fun `round trips tap info, including a missing read time`() {
        val store = SharedPreferencesMockProcessorStore(context, "store-tap")
        val wallet = ProcessedCharge("k1", true, "MOCK-1", 100, "USD", tap = MockTapInfo(MockEntryMode.CONTACTLESS_WALLET, 1200))
        val chip = ProcessedCharge("k2", true, "MOCK-2", 100, "USD", tap = MockTapInfo(MockEntryMode.CHIP, null))
        store.put(wallet)
        store.put(chip)
        assertEquals(wallet, store.get("k1"))
        assertEquals(chip, store.get("k2"))
    }

    @Test
    fun `reads 0_1_0 records without tap info as the default tap`() {
        context.getSharedPreferences("store-legacy", Context.MODE_PRIVATE).edit()
            .putString("old", "A|MOCK-9|500|USD|").commit()
        val charge = SharedPreferencesMockProcessorStore(context, "store-legacy").get("old")
        assertEquals(ProcessedCharge("old", true, "MOCK-9", 500, "USD", tap = MockTapInfo()), charge)
    }
}
