package dev.posguard.ledger

import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveryOutcome
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecoveryTest {

    private val usd = Money(899, "USD")

    /** Puts a fresh transaction into [state] (CREATED, SUBMITTED, or UNKNOWN). */
    private suspend fun TransactionLedger.txnIn(state: TxnState): TxnId {
        val id = begin(usd, Tender.CARD)
        if (state == TxnState.CREATED) return id
        markSubmitted(id)
        if (state == TxnState.SUBMITTED) return id
        markResult(id, EngineResult.Unknown)
        check(state == TxnState.UNKNOWN)
        return id
    }

    private data class Case(
        val start: TxnState,
        val status: EngineStatus,
        val expectedState: TxnState,
        val expectedOutcome: RecoveryOutcome,
    )

    private val matrix = listOf(
        Case(TxnState.CREATED, EngineStatus.Authorized("e"), TxnState.AUTHORIZED, RecoveryOutcome.RESOLVED),
        Case(TxnState.CREATED, EngineStatus.Declined("e"), TxnState.DECLINED, RecoveryOutcome.RESOLVED),
        Case(TxnState.CREATED, EngineStatus.NotProcessed, TxnState.CREATED, RecoveryOutcome.SAFE_TO_RESUBMIT),
        Case(TxnState.CREATED, EngineStatus.Unreachable, TxnState.CREATED, RecoveryOutcome.UNRESOLVED),
        Case(TxnState.SUBMITTED, EngineStatus.Authorized("e"), TxnState.AUTHORIZED, RecoveryOutcome.RESOLVED),
        Case(TxnState.SUBMITTED, EngineStatus.Declined("e"), TxnState.DECLINED, RecoveryOutcome.RESOLVED),
        Case(TxnState.SUBMITTED, EngineStatus.NotProcessed, TxnState.UNKNOWN, RecoveryOutcome.SAFE_TO_RESUBMIT),
        Case(TxnState.SUBMITTED, EngineStatus.Unreachable, TxnState.UNKNOWN, RecoveryOutcome.UNRESOLVED),
        Case(TxnState.UNKNOWN, EngineStatus.Authorized("e"), TxnState.AUTHORIZED, RecoveryOutcome.RESOLVED),
        Case(TxnState.UNKNOWN, EngineStatus.Declined("e"), TxnState.DECLINED, RecoveryOutcome.RESOLVED),
        Case(TxnState.UNKNOWN, EngineStatus.NotProcessed, TxnState.UNKNOWN, RecoveryOutcome.SAFE_TO_RESUBMIT),
        Case(TxnState.UNKNOWN, EngineStatus.Unreachable, TxnState.UNKNOWN, RecoveryOutcome.UNRESOLVED),
    )

    @Test
    fun `recovery matrix covers every pending state and engine status`() = runTest {
        for (case in matrix) {
            val engine = FakeEngineStatus()
            val ledger = testLedger(engine)
            val id = ledger.txnIn(case.start)
            engine.statuses[ledger.get(id)!!.idempotencyKey] = case.status

            val recovered = ledger.recoverPending().single()

            val label = "${case.start} + ${case.status}"
            assertEquals(label, case.start, recovered.previousState)
            assertEquals(label, case.expectedState, recovered.record.state)
            assertEquals(label, case.expectedOutcome, recovered.outcome)
            assertEquals(label, case.expectedState, ledger.get(id)!!.state)
        }
    }

    @Test
    fun `engine reference from status is recorded`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val id = ledger.txnIn(TxnState.UNKNOWN)
        engine.statuses[ledger.get(id)!!.idempotencyKey] = EngineStatus.Authorized("eng-42")

        ledger.recoverPending()

        assertEquals("eng-42", ledger.get(id)!!.engineReference)
    }

    @Test
    fun `resubmit and void from UNKNOWN are allowed only after NotProcessed`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val resubmit = ledger.txnIn(TxnState.UNKNOWN)
        val void = ledger.txnIn(TxnState.UNKNOWN)
        engine.statuses[ledger.get(resubmit)!!.idempotencyKey] = EngineStatus.NotProcessed
        engine.statuses[ledger.get(void)!!.idempotencyKey] = EngineStatus.NotProcessed

        ledger.recoverPending()
        ledger.markSubmitted(resubmit)
        ledger.markVoided(void)

        assertEquals(TxnState.SUBMITTED, ledger.get(resubmit)!!.state)
        assertEquals(TxnState.VOIDED, ledger.get(void)!!.state)
    }

    @Test
    fun `resubmit permission is single use`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val id = ledger.txnIn(TxnState.UNKNOWN)
        engine.statuses[ledger.get(id)!!.idempotencyKey] = EngineStatus.NotProcessed
        ledger.recoverPending()

        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Unknown) // dropped again

        assertTrue(runCatching { ledger.markSubmitted(id) }.isFailure)
    }

    @Test
    fun `status source that throws counts as unreachable`() = runTest {
        val engine = FakeEngineStatus().apply { throwOnQuery = IllegalStateException("boom") }
        val ledger = testLedger(engine)
        val id = ledger.txnIn(TxnState.SUBMITTED)

        val recovered = ledger.recoverPending().single()

        assertEquals(RecoveryOutcome.UNRESOLVED, recovered.outcome)
        assertEquals(TxnState.UNKNOWN, ledger.get(id)!!.state)
    }

    @Test
    fun `settled transactions are not queried`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("e"))
        ledger.markCompleted(id)

        assertEquals(emptyList<Any>(), ledger.recoverPending())
        assertEquals(0, engine.queried.size)
    }

    @Test
    fun `app crash mid-payment is recovered with the original key and no double charge`() = runTest {
        val dbName = "crash-test-${System.nanoTime()}.db"
        val engine = FakeEngineStatus()

        // Before the crash: the payment was handed to the engine, then the app died.
        val beforeCrash = testLedger(engine, databaseName = dbName)
        val id = beforeCrash.begin(usd, Tender.CARD)
        beforeCrash.markSubmitted(id)
        val originalKey = beforeCrash.get(id)!!.idempotencyKey

        // The engine did process it while the app was down.
        engine.statuses[originalKey] = EngineStatus.Authorized("eng-crash")

        // Relaunch: a new ledger instance on the same journal file.
        val afterRelaunch = testLedger(engine, databaseName = dbName)
        val recovered = afterRelaunch.recoverPending().single()

        assertEquals(RecoveryOutcome.RESOLVED, recovered.outcome)
        assertEquals(TxnState.AUTHORIZED, afterRelaunch.get(id)!!.state)
        assertEquals(listOf(originalKey), engine.queried)
        // Resubmitting is impossible now. The journal refuses it, so no double charge.
        assertTrue(runCatching { afterRelaunch.markSubmitted(id) }.isFailure)
        context.deleteDatabase(dbName)
    }
}
