package dev.posguard.ledger

import androidx.room.Room
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TxnOutcome
import dev.posguard.core.ledger.TxnState
import dev.posguard.ledger.internal.LedgerDatabase
import dev.posguard.ledger.internal.MIGRATION_1_2
import dev.posguard.ledger.internal.MIGRATION_2_3
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutcomeTest {

    private val usd = Money(1000, "USD")

    @Test
    fun `approved outcome survives completion`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)
        assertNull(ledger.get(id)!!.outcome)

        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("eng-1"))
        ledger.markCompleted(id)

        val record = ledger.get(id)!!
        assertEquals(TxnState.COMPLETED, record.state)
        assertEquals(TxnOutcome.APPROVED, record.outcome)
    }

    @Test
    fun `declined outcome survives completion`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Declined(declineCode = "05"))
        ledger.markCompleted(id)

        val record = ledger.get(id)!!
        assertEquals(TxnState.COMPLETED, record.state)
        assertEquals(TxnOutcome.DECLINED, record.outcome)
    }

    @Test
    fun `unknown has no outcome and voiding keeps none`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Unknown)
        assertNull(ledger.get(id)!!.outcome)

        engine.statuses[ledger.get(id)!!.idempotencyKey] = EngineStatus.NotProcessed
        ledger.recoverPending()
        ledger.markVoided(id)
        assertNull(ledger.get(id)!!.outcome)
    }

    @Test
    fun `recovery records the outcome`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val approved = ledger.begin(usd, Tender.CARD).also { ledger.markSubmitted(it) }
        val declined = ledger.begin(usd, Tender.CARD).also { ledger.markSubmitted(it) }
        engine.statuses[ledger.get(approved)!!.idempotencyKey] = EngineStatus.Authorized("a")
        engine.statuses[ledger.get(declined)!!.idempotencyKey] = EngineStatus.Declined("d", "05")

        ledger.recoverPending()

        assertEquals(TxnOutcome.APPROVED, ledger.get(approved)!!.outcome)
        assertEquals(TxnOutcome.DECLINED, ledger.get(declined)!!.outcome)
    }

    /**
     * Builds a real v1 database from the committed v1 schema (posguard-ledger/schemas/.../1.json),
     * then opens it with Room and the migrations. Room validates the migrated schema against the latest
     * on open, so a wrong migration fails here.
     */
    @Test
    fun `migration 1 to 2 adds outcome and backfills known answers without losing rows`() {
        val name = "migration-test-${System.nanoTime()}.db"
        createDatabaseFromSchema(1, name) { db ->
            fun row(id: String, state: String) = db.execSQL(
                "INSERT INTO posguard_txn (id, idempotencyKey, amountCents, currency, tender, state, " +
                    "createdAtMillis, updatedAtMillis, engineReference, resubmitAllowed, version) " +
                    "VALUES ('$id', 'k-$id', 100, 'USD', 'CARD', '$state', 0, 0, NULL, 0, 0)",
            )
            row("a", "AUTHORIZED")
            row("d", "DECLINED")
            row("c", "COMPLETED")
            row("s", "SUBMITTED")
        }

        val room = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        val outcomes = runBlocking {
            listOf("a", "d", "c", "s").associateWith { room.transactionDao().get(it)!!.outcome }
        }
        room.close()
        context.deleteDatabase(name)

        assertEquals(mapOf("a" to "APPROVED", "d" to "DECLINED", "c" to null, "s" to null), outcomes)
    }
}
