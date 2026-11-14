package dev.posguard.ledger.internal

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompareAndSetTest {

    @Test
    fun `stale version write is rejected`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java)
            .build()
        val dao = db.transactionDao()
        dao.insert(
            TransactionEntity(
                id = "t1", idempotencyKey = "k1", amountCents = 100, currency = "USD", tender = "CARD",
                state = "CREATED", createdAtMillis = 0, updatedAtMillis = 0, engineReference = null,
                resubmitAllowed = false, version = 0,
            ),
        )
        val stale = dao.get("t1")!!

        assertEquals(1, dao.compareAndSet("t1", stale.version, "SUBMITTED", 1, null, false, null))
        // A second writer that read the same version must lose.
        assertEquals(0, dao.compareAndSet("t1", stale.version, "VOIDED", 2, null, false, null))

        val row = dao.get("t1")!!
        assertEquals("SUBMITTED", row.state)
        assertEquals(1L, row.version)
        db.close()
    }
}
