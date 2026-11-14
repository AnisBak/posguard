package dev.posguard.mockengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveryOutcome
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import dev.posguard.ledger.PosGuardLedger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Demo scenarios 1, 4, and 5 (plus the other failure modes), run end to end:
 * the real Room ledger against the mock engine. The invariant every test
 * checks is that the processor charged each payment **at most once**.
 */
@RunWith(RobolectricTestRunner::class)
class EndToEndScenarioTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val amount = Money(1250, "USD")
    private val dbName = "e2e-${System.nanoTime()}.db"
    private val prefsName = "e2e-processor-${System.nanoTime()}"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
        context.deleteSharedPreferences(prefsName)
    }

    private fun newEngine(persistent: Boolean = false) = MockPaymentEngine.Builder()
        .apply { if (persistent) store(SharedPreferencesMockProcessorStore(context, prefsName)) }
        .build()

    private fun newLedger(engine: MockPaymentEngine, persistent: Boolean = false): TransactionLedger =
        PosGuardLedger.Builder(context, engine)
            .apply { if (persistent) databaseName(dbName) else inMemory() }
            .build()

    /** The checkout flow an integrating app follows (see docs/ledger.md). */
    private suspend fun startCheckout(ledger: TransactionLedger): Pair<TxnId, ChargeRequest> {
        val id = ledger.begin(amount, Tender.CARD)
        val request = ChargeRequest(amount, Tender.CARD, ledger.get(id)!!.idempotencyKey)
        ledger.markSubmitted(id)
        return id to request
    }

    private suspend fun checkout(ledger: TransactionLedger, engine: MockPaymentEngine): TxnId {
        val (id, request) = startCheckout(ledger)
        val result = try {
            engine.charge(request)
        } catch (_: MockEngineCrashException) {
            EngineResult.Unknown
        }
        ledger.markResult(id, result)
        return id
    }

    @Test
    fun `scenario 1 - normal payment completes with one charge`() = runTest {
        val engine = newEngine()
        val ledger = newLedger(engine)

        val id = checkout(ledger, engine)
        ledger.markCompleted(id)

        assertEquals(TxnState.COMPLETED, ledger.get(id)!!.state)
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `decline is recorded and closed`() = runTest {
        val engine = newEngine().apply { setNextScenario(MockScenario.DECLINE) }
        val ledger = newLedger(engine)

        val id = checkout(ledger, engine)
        assertEquals(TxnState.DECLINED, ledger.get(id)!!.state)
        ledger.markCompleted(id)
        assertEquals(TxnState.COMPLETED, ledger.get(id)!!.state)
    }

    @Test
    fun `scenario 4 - network drop after submit resolves on reconnect without double charge`() = runTest {
        val engine = newEngine().apply { setNextScenario(MockScenario.NETWORK_DROP_AFTER_SUBMIT) }
        val ledger = newLedger(engine)

        val id = checkout(ledger, engine)
        assertEquals(TxnState.UNKNOWN, ledger.get(id)!!.state)

        // Still offline: recovery must not guess.
        assertEquals(RecoveryOutcome.UNRESOLVED, ledger.recoverPending().single().outcome)
        assertEquals(TxnState.UNKNOWN, ledger.get(id)!!.state)

        engine.reconnect()
        val recovered = ledger.recoverPending().single()

        assertEquals(RecoveryOutcome.RESOLVED, recovered.outcome)
        assertEquals(TxnState.AUTHORIZED, ledger.get(id)!!.state)
        val charge = engine.processedCharges().single()
        assertEquals(charge.engineReference, ledger.get(id)!!.engineReference)
    }

    @Test
    fun `scenario 5 - app killed after the engine charged is recovered on relaunch`() = runTest {
        val engineBefore = newEngine(persistent = true)
        val ledgerBefore = newLedger(engineBefore, persistent = true)
        val (id, request) = startCheckout(ledgerBefore)
        engineBefore.charge(request)
        // The app dies here, before markResult. Nothing in memory survives.

        val engineAfter = newEngine(persistent = true)
        val ledgerAfter = newLedger(engineAfter, persistent = true)
        val recovered = ledgerAfter.recoverPending().single()

        assertEquals(TxnState.SUBMITTED, recovered.previousState)
        assertEquals(RecoveryOutcome.RESOLVED, recovered.outcome)
        assertEquals(TxnState.AUTHORIZED, ledgerAfter.get(id)!!.state)
        assertEquals(1, engineAfter.processedCharges().size)
    }

    @Test
    fun `engine crash is recovered as authorized`() = runTest {
        val engine = newEngine().apply { setNextScenario(MockScenario.ENGINE_CRASH) }
        val ledger = newLedger(engine)

        val id = checkout(ledger, engine)
        assertEquals(TxnState.UNKNOWN, ledger.get(id)!!.state)

        ledger.recoverPending()
        assertEquals(TxnState.AUTHORIZED, ledger.get(id)!!.state)
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `timeout is safely resubmitted with the same key and charged once`() = runTest {
        val engine = newEngine().apply { setNextScenario(MockScenario.TIMEOUT) }
        val ledger = newLedger(engine)

        val id = checkout(ledger, engine)
        assertEquals(TxnState.UNKNOWN, ledger.get(id)!!.state)
        assertEquals(RecoveryOutcome.SAFE_TO_RESUBMIT, ledger.recoverPending().single().outcome)

        val record = ledger.get(id)!!
        ledger.markSubmitted(id)
        ledger.markResult(id, engine.charge(ChargeRequest(record.amount, record.tender, record.idempotencyKey)))

        assertEquals(TxnState.AUTHORIZED, ledger.get(id)!!.state)
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `duplicate engine response is absorbed by the ledger`() = runTest {
        val engine = newEngine().apply { setNextScenario(MockScenario.DUPLICATE_RESPONSE) }
        val ledger = newLedger(engine)

        engine.lateResponses.test {
            val id = checkout(ledger, engine)
            val late = awaitItem()
            // The app forwards the late response; the ledger treats it as a no-op.
            ledger.markResult(id, late.result)

            assertEquals(TxnState.AUTHORIZED, ledger.get(id)!!.state)
        }
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `retrying the same key after authorization never charges twice`() = runTest {
        val engine = newEngine()
        val ledger = newLedger(engine)
        val id = checkout(ledger, engine)
        val record = ledger.get(id)!!

        // A buggy integration retries with the same key: the processor deduplicates.
        val again = engine.charge(ChargeRequest(record.amount, record.tender, record.idempotencyKey))

        assertTrue(again is EngineResult.Authorized)
        assertEquals(record.engineReference, (again as EngineResult.Authorized).engineReference)
        assertEquals(1, engine.processedCharges().size)
    }
}
