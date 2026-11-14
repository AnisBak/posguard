@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.posguard.mockengine

import app.cash.turbine.test
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class MockPaymentEngineTest {

    private var keyCounter = 0
    private fun request(cents: Long = 1250) =
        ChargeRequest(Money(cents, "USD"), Tender.CARD, IdempotencyKey("key-${++keyCounter}"))

    private fun TestScope.engine(
        store: MockProcessorStore = InMemoryMockProcessorStore(),
        scenario: MockScenario = MockScenario.APPROVE,
    ) = MockPaymentEngine.Builder()
        .store(store)
        .dispatcher(StandardTestDispatcher(testScheduler))
        .defaultScenario(scenario)
        .build()

    @Test
    fun `approve authorizes and records one charge`() = runTest {
        val engine = engine()
        val req = request()

        val result = engine.charge(req) as EngineResult.Authorized

        assertTrue(result.engineReference.startsWith("MOCK-"))
        val charge = engine.processedCharges().single()
        assertEquals(req.idempotencyKey.value, charge.idempotencyKey)
        assertEquals(1250L, charge.amountCents)
        assertTrue(charge.approved)
    }

    @Test
    fun `decline returns fake decline code`() = runTest {
        val result = engine(scenario = MockScenario.DECLINE).charge(request()) as EngineResult.Declined
        assertEquals(MockPaymentEngine.FAKE_DECLINE_CODE, result.declineCode)
    }

    @Test
    fun `timeout returns unknown and processes nothing`() = runTest {
        val engine = engine(scenario = MockScenario.TIMEOUT)
        val req = request()

        assertEquals(EngineResult.Unknown, engine.charge(req))
        assertEquals(emptyList<ProcessedCharge>(), engine.processedCharges())
        assertEquals(EngineStatus.NotProcessed, engine.queryStatus(req.idempotencyKey))
    }

    @Test
    fun `network drop after submit processes the charge but returns unknown and goes offline`() = runTest {
        val engine = engine(scenario = MockScenario.NETWORK_DROP_AFTER_SUBMIT)
        val req = request()

        assertEquals(EngineResult.Unknown, engine.charge(req))
        assertFalse(engine.networkAvailable.value)
        assertEquals(EngineStatus.Unreachable, engine.queryStatus(req.idempotencyKey))

        engine.reconnect()
        assertTrue(engine.queryStatus(req.idempotencyKey) is EngineStatus.Authorized)
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `duplicate response is delivered again on late responses`() = runTest {
        val engine = engine(scenario = MockScenario.DUPLICATE_RESPONSE)
        val req = request()

        engine.lateResponses.test {
            val result = engine.charge(req)
            assertEquals(LateResponse(req.idempotencyKey, result), awaitItem())
        }
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `engine crash throws after the processor approved`() = runTest {
        val engine = engine(scenario = MockScenario.ENGINE_CRASH)
        val req = request()

        val error = runCatching { engine.charge(req) }.exceptionOrNull()

        assertTrue(error is MockEngineCrashException)
        assertTrue(engine.queryStatus(req.idempotencyKey) is EngineStatus.Authorized)
    }

    @Test
    fun `same key is never charged twice`() = runTest {
        val engine = engine()
        val req = request()

        val first = engine.charge(req)
        engine.setNextScenario(MockScenario.DECLINE) // the processor still returns the original result
        val second = engine.charge(req)

        assertEquals(first, second)
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `concurrent charges with one key process once`() = runTest {
        val engine = engine()
        val req = request()

        val results = (1..10).map { async { engine.charge(req) } }.awaitAll()

        assertEquals(1, results.toSet().size)
        assertEquals(1, engine.processedCharges().size)
    }

    @Test
    fun `offline charge never reaches the processor`() = runTest {
        val engine = engine()
        val req = request()
        engine.disconnect()

        assertEquals(EngineResult.Unknown, engine.charge(req))
        engine.reconnect()
        assertEquals(EngineStatus.NotProcessed, engine.queryStatus(req.idempotencyKey))
    }

    @Test
    fun `next scenario applies once then falls back to default`() = runTest {
        val engine = engine()
        engine.setNextScenario(MockScenario.DECLINE)

        assertTrue(engine.charge(request()) is EngineResult.Declined)
        assertTrue(engine.charge(request()) is EngineResult.Authorized)
    }

    @Test
    fun `query status reports declined with reference`() = runTest {
        val engine = engine(scenario = MockScenario.DECLINE)
        val req = request()
        val result = engine.charge(req) as EngineResult.Declined

        assertEquals(
            EngineStatus.Declined(result.engineReference, MockPaymentEngine.FAKE_DECLINE_CODE),
            engine.queryStatus(req.idempotencyKey),
        )
    }

    @Test
    fun `reset clears processor and restores network and scenario`() = runTest {
        val engine = engine()
        engine.charge(request())
        engine.disconnect()
        engine.setNextScenario(MockScenario.DECLINE)

        engine.reset()

        assertEquals(emptyList<ProcessedCharge>(), engine.processedCharges())
        assertTrue(engine.networkAvailable.value)
        assertTrue(engine.charge(request()) is EngineResult.Authorized)
    }

    @Test
    fun `latency uses virtual time`() = runTest {
        val engine = MockPaymentEngine.Builder()
            .dispatcher(StandardTestDispatcher(testScheduler))
            .latency(800.milliseconds)
            .build()

        engine.charge(request())

        assertEquals(800L, testScheduler.currentTime)
    }

    @Test
    fun `label is the required demo banner`() {
        assertEquals("DEMO — NOT A REAL PAYMENT", MockPaymentEngine.LABEL)
    }
}
