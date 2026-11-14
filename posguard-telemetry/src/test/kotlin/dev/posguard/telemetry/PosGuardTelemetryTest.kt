@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.posguard.telemetry

import dev.posguard.core.event.GuardEvent
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PosGuardTelemetryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val device = DeviceContext("Acme", "Pixel Test", "15", 35, "0.1.0")

    private fun TestScope.telemetry(
        vararg sinks: TelemetrySink,
        batchSize: Int = 20,
        maxBuffered: Int = 500,
        scope: kotlinx.coroutines.CoroutineScope = backgroundScope,
    ): PosGuardTelemetry {
        val ids = AtomicInteger()
        return PosGuardTelemetry.Builder(device)
            .apply { sinks.forEach(::addSink) }
            .batchSize(batchSize)
            .maxBufferedEvents(maxBuffered)
            .scope(scope)
            .clock(Clock.fixed(now, ZoneOffset.UTC))
            .idGenerator { "evt-${ids.incrementAndGet()}" }
            .build()
    }

    private fun txnEvent(n: Int) =
        GuardEvent.TxnStateChanged(TxnId("t-$n"), null, TxnState.CREATED, now)

    /** Sink that fails until [healthy] is set. */
    private class FlakySink : TelemetrySink {
        var healthy = false
        val received = mutableListOf<TelemetryEvent>()
        override suspend fun send(events: List<TelemetryEvent>) {
            if (!healthy) error("sink down")
            received += events
        }
    }

    @Test
    fun `card data in recorded message and attributes never reaches a sink`() = runTest {
        val sink = InMemorySink()
        val telemetry = telemetry(sink)

        telemetry.record(
            EventType.APP_ERROR,
            message = "decline for 4111 1111 1111 1111, contact jane@example.com",
            attributes = mapOf("pan" to "4111111111111111", "note" to "card 5555555555554444"),
        )
        assertTrue(telemetry.flush())

        val json = sink.events.value.single().toJson()
        listOf("4111111111111111", "5555555555554444", "jane@example.com").forEach {
            assertFalse("$it leaked: $json", json.replace(" ", "").contains(it))
        }
        assertFalse(json.contains("4111 1111"))
    }

    @Test
    fun `guard events are mapped and delivered`() = runTest {
        val sink = InMemorySink()
        val telemetry = telemetry(sink)

        telemetry.onEvent(txnEvent(1))
        telemetry.flush()

        val event = sink.events.value.single()
        assertEquals(EventType.TXN_STATE_CHANGED, event.type)
        assertEquals("t-1", event.txn!!.txnId)
        assertEquals(device, event.device)
    }

    @Test
    fun `buffer cap drops oldest events and counts them`() = runTest {
        // The sink is down, so nothing drains and the buffer fills.
        val unflushed = FlakySink()
        val stuck = telemetry(unflushed, batchSize = 5, maxBuffered = 5)

        repeat(8) { stuck.onEvent(txnEvent(it)) }
        runCurrent()

        assertEquals(5, stuck.bufferedCount)
        assertEquals(3L, stuck.droppedCount)
        unflushed.healthy = true
        assertTrue(stuck.flush())
        assertEquals((3..7).map { "t-$it" }, unflushed.received.map { it.txn!!.txnId })
        assertEquals(0, stuck.bufferedCount)
    }

    @Test
    fun `reaching batch size flushes automatically`() = runTest {
        val sink = InMemorySink()
        val telemetry = telemetry(sink, batchSize = 3)

        repeat(2) { telemetry.onEvent(txnEvent(it)) }
        runCurrent()
        assertEquals(0, sink.events.value.size)

        telemetry.onEvent(txnEvent(2))
        runCurrent()
        assertEquals(3, sink.events.value.size)
        assertEquals(0, telemetry.bufferedCount)
    }

    @Test
    fun `failing sink keeps events until it recovers`() = runTest {
        val good = InMemorySink()
        val flaky = FlakySink()
        val telemetry = telemetry(good, flaky)

        repeat(3) { telemetry.onEvent(txnEvent(it)) }
        assertFalse(telemetry.flush())
        assertEquals(3, telemetry.bufferedCount)

        flaky.healthy = true
        assertTrue(telemetry.flush())

        assertEquals(0, telemetry.bufferedCount)
        assertEquals(3, flaky.received.size)
        // The healthy sink saw the batch twice; event ids allow de-duplication.
        assertEquals(3, good.events.value.map { it.eventId }.toSet().size)
    }

    @Test
    fun `throwing sink never affects the caller`() = runTest {
        val telemetry = telemetry(TelemetrySink { throw IllegalStateException("down") }, batchSize = 1)
        telemetry.onEvent(txnEvent(1)) // triggers an automatic flush that fails
        runCurrent()
        assertEquals(1, telemetry.bufferedCount)
    }

    @Test
    fun `record rejects posguard event types`() = runTest {
        val telemetry = telemetry()
        assertThrows(IllegalArgumentException::class.java) {
            telemetry.record(EventType.TXN_STATE_CHANGED, "x")
        }
    }

    @Test
    fun `file sink writes valid JSON lines and rotates at the size limit`() = runTest {
        val dir = tmp.newFolder("telemetry")
        val sink = JsonLinesFileSink(dir, maxFileBytes = 600, maxFiles = 2)
        val telemetry = telemetry(sink, batchSize = 1)

        repeat(10) {
            telemetry.record(EventType.APP_EVENT, "event $it", mapOf("n" to "$it"))
            telemetry.flush()
        }

        val files = sink.files()
        assertEquals(2, files.size)
        assertTrue(files.all { it.length() <= 600 })
        val lines = files.flatMap { it.readLines() }.filter { it.isNotBlank() }
        lines.forEach { JSONObject(it) } // every line is valid JSON
        // The newest events survive rotation, in order.
        val ns = lines.map { JSONObject(it).getJSONObject("attributes").getString("n").toInt() }
        assertEquals(ns.sorted(), ns)
        assertEquals(9, ns.last())
    }

    @Test
    fun `builder validates limits`() {
        assertThrows(IllegalArgumentException::class.java) { PosGuardTelemetry.Builder(device).batchSize(0) }
        assertThrows(IllegalArgumentException::class.java) {
            PosGuardTelemetry.Builder(device).batchSize(10).maxBufferedEvents(5).build()
        }
    }
}
