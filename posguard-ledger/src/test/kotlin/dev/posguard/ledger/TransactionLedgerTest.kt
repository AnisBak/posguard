package dev.posguard.ledger

import app.cash.turbine.test
import dev.posguard.core.event.GuardEvent
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.IllegalTxnTransitionException
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class TransactionLedgerTest {

    private val usd = Money(1250, "USD")

    @Test
    fun `begin persists only the allowed fields in CREATED`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)

        val record = ledger.get(id)!!
        assertEquals(id, record.id)
        assertEquals(usd, record.amount)
        assertEquals(Tender.CARD, record.tender)
        assertEquals(TxnState.CREATED, record.state)
        assertEquals(TEST_NOW, record.createdAt)
        assertEquals(TEST_NOW, record.updatedAt)
        assertNull(record.engineReference)
    }

    @Test
    fun `every transaction gets a unique idempotency key`() = runTest {
        val ledger = testLedger()
        val keys = (1..50).map { ledger.get(ledger.begin(usd, Tender.CARD))!!.idempotencyKey }
        assertEquals(50, keys.toSet().size)
    }

    @Test
    fun `happy path reaches COMPLETED and emits every transition`() = runTest {
        val events = CopyOnWriteArrayList<GuardEvent>()
        val ledger = testLedger(events = events)

        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("eng-1"))
        ledger.markCompleted(id)

        val record = ledger.get(id)!!
        assertEquals(TxnState.COMPLETED, record.state)
        assertEquals("eng-1", record.engineReference)
        assertEquals(
            listOf(
                null to TxnState.CREATED,
                TxnState.CREATED to TxnState.SUBMITTED,
                TxnState.SUBMITTED to TxnState.AUTHORIZED,
                TxnState.AUTHORIZED to TxnState.COMPLETED,
            ),
            events.map { (it as GuardEvent.TxnStateChanged).let { e -> e.from to e.to } },
        )
    }

    @Test
    fun `observe emits current state then each change`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)

        ledger.observe(id).test {
            assertEquals(TxnState.CREATED, awaitItem())
            ledger.markSubmitted(id)
            assertEquals(TxnState.SUBMITTED, awaitItem())
            ledger.markResult(id, EngineResult.Declined(declineCode = "05"))
            assertEquals(TxnState.DECLINED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeRecent lists newest first, honors the limit, and updates on change`() = runTest {
        val ledger = testLedger()
        val first = ledger.begin(usd, Tender.CARD)
        val second = ledger.begin(usd, Tender.CARD)
        val third = ledger.begin(usd, Tender.EBT_SNAP)

        ledger.observeRecent(limit = 2).test {
            assertEquals(listOf(third, second), awaitItem().map { it.id })
            ledger.markSubmitted(third)
            val updated = awaitItem()
            assertEquals(listOf(third, second), updated.map { it.id })
            assertEquals(TxnState.SUBMITTED, updated.first().state)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(TxnState.CREATED, ledger.get(first)!!.state)
    }

    @Test
    fun `illegal transition throws and leaves the journal unchanged`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)

        val e = runCatching { ledger.markResult(id, EngineResult.Authorized("eng-1")) }.exceptionOrNull()
        assertEquals(IllegalTxnTransitionException::class, e!!::class)
        assertEquals(TxnState.CREATED, ledger.get(id)!!.state)
    }

    @Test
    fun `completing twice is rejected`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("eng-1"))
        ledger.markCompleted(id)

        val e = runCatching { ledger.markCompleted(id) }.exceptionOrNull()
        assertEquals(IllegalTxnTransitionException::class, e!!::class)
    }

    @Test
    fun `declined can never become authorized`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Declined("eng-1"))

        val e = runCatching { ledger.markResult(id, EngineResult.Authorized("eng-1")) }.exceptionOrNull()
        assertEquals(IllegalTxnTransitionException::class, e!!::class)
        assertEquals(TxnState.DECLINED, ledger.get(id)!!.state)
    }

    @Test
    fun `duplicate identical engine response is a no-op`() = runTest {
        val events = CopyOnWriteArrayList<GuardEvent>()
        val ledger = testLedger(events = events)
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("eng-1"))
        val before = events.size

        ledger.markResult(id, EngineResult.Authorized("eng-1"))

        assertEquals(TxnState.AUTHORIZED, ledger.get(id)!!.state)
        assertEquals(before, events.size)
    }

    @Test
    fun `conflicting duplicate engine response throws`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("eng-1"))

        val e = runCatching { ledger.markResult(id, EngineResult.Authorized("eng-OTHER")) }.exceptionOrNull()
        assertEquals(IllegalTxnTransitionException::class, e!!::class)
        assertEquals("eng-1", ledger.get(id)!!.engineReference)
    }

    @Test
    fun `network drop after submit moves to UNKNOWN`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Unknown)
        assertEquals(TxnState.UNKNOWN, ledger.get(id)!!.state)
    }

    @Test
    fun `UNKNOWN cannot be resubmitted or voided before the engine confirms`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Unknown)

        assertEquals(
            IllegalTxnTransitionException::class,
            runCatching { ledger.markSubmitted(id) }.exceptionOrNull()!!::class,
        )
        assertEquals(
            IllegalTxnTransitionException::class,
            runCatching { ledger.markVoided(id) }.exceptionOrNull()!!::class,
        )
        assertEquals(TxnState.UNKNOWN, ledger.get(id)!!.state)
    }

    @Test
    fun `CREATED can be voided`() = runTest {
        val ledger = testLedger()
        val id = ledger.begin(usd, Tender.EBT_SNAP)
        ledger.markVoided(id)
        assertEquals(TxnState.VOIDED, ledger.get(id)!!.state)
    }

    @Test
    fun `unknown id throws and get returns null`() = runTest {
        val ledger = testLedger()
        val missing = TxnId("does-not-exist")
        assertNull(ledger.get(missing))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { ledger.markSubmitted(missing) }
        }
    }

    @Test
    fun `throwing listener does not break the ledger`() = runTest {
        val ledger = PosGuardLedger.Builder(context, FakeEngineStatus())
            .inMemory()
            .addListener { throw RuntimeException("telemetry down") }
            .build()
        val id = ledger.begin(usd, Tender.CARD)
        ledger.markSubmitted(id)
        assertEquals(TxnState.SUBMITTED, ledger.get(id)!!.state)
    }
}
