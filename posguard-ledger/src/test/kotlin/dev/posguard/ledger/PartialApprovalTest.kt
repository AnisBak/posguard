package dev.posguard.ledger

import androidx.room.Room
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TxnState
import dev.posguard.ledger.internal.LedgerDatabase
import dev.posguard.ledger.internal.MIGRATION_1_2
import dev.posguard.ledger.internal.MIGRATION_2_3
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PartialApprovalTest {

    private val requested = Money(1000, "USD")

    @Test
    fun `partial approval is recorded and survives completion`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(requested, Tender.EBT_SNAP)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("e-1", approvedAmount = Money(600, "USD")))
        ledger.markCompleted(id)

        val record = ledger.get(id)!!
        assertEquals(TxnState.COMPLETED, record.state)
        assertEquals(requested, record.amount)
        assertEquals(Money(600, "USD"), record.approvedAmount)
    }

    @Test
    fun `full approval leaves approved amount null`() = runTest {
        val ledger = testLedger()
        val full = ledger.begin(requested, Tender.CARD)
        ledger.markSubmitted(full)
        ledger.markResult(full, EngineResult.Authorized("e-1"))
        assertNull(ledger.get(full)!!.approvedAmount)

        // An engine that echoes the full amount is a full approval too.
        val echoed = ledger.begin(requested, Tender.CARD)
        ledger.markSubmitted(echoed)
        ledger.markResult(echoed, EngineResult.Authorized("e-2", approvedAmount = requested))
        assertNull(ledger.get(echoed)!!.approvedAmount)
    }

    @Test
    fun `impossible approved amounts are rejected and nothing is written`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(requested, Tender.EBT_SNAP)
        ledger.markSubmitted(id)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ledger.markResult(id, EngineResult.Authorized("e", approvedAmount = Money(1001, "USD"))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ledger.markResult(id, EngineResult.Authorized("e", approvedAmount = Money(500, "CAD"))) }
        }
        assertEquals(TxnState.SUBMITTED, ledger.get(id)!!.state)
    }

    @Test
    fun `recovery records a partial approval and ignores an impossible one`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val partial = ledger.begin(requested, Tender.EBT_SNAP).also { ledger.markSubmitted(it) }
        val bogus = ledger.begin(requested, Tender.EBT_SNAP).also { ledger.markSubmitted(it) }
        engine.statuses[ledger.get(partial)!!.idempotencyKey] = EngineStatus.Authorized("p", Money(250, "USD"))
        engine.statuses[ledger.get(bogus)!!.idempotencyKey] = EngineStatus.Authorized("b", Money(5000, "USD"))

        ledger.recoverPending()

        assertEquals(Money(250, "USD"), ledger.get(partial)!!.approvedAmount)
        assertEquals(TxnState.AUTHORIZED, ledger.get(bogus)!!.state)
        assertNull(ledger.get(bogus)!!.approvedAmount)
    }

    @Test
    fun `migration 2 to 3 adds approved amount without losing rows`() {
        val name = "migration23-test-${System.nanoTime()}.db"
        createDatabaseFromSchema(2, name) { db ->
            db.execSQL(
                "INSERT INTO posguard_txn (id, idempotencyKey, amountCents, currency, tender, state, " +
                    "createdAtMillis, updatedAtMillis, engineReference, resubmitAllowed, version, outcome) " +
                    "VALUES ('a', 'k-a', 100, 'USD', 'CARD', 'COMPLETED', 0, 0, 'ref', 0, 3, 'APPROVED')",
            )
        }

        val room = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        val row = runBlocking { room.transactionDao().get("a")!! }
        room.close()
        context.deleteDatabase(name)

        assertEquals("APPROVED", row.outcome)
        assertEquals(100L, row.amountCents)
        assertNull(row.approvedAmountCents)
    }
}
