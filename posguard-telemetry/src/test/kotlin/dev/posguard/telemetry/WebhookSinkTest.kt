package dev.posguard.telemetry

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class WebhookSinkTest {

    private class Received(val headers: Map<String, String?>, val body: String)

    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<Received>()

    /** Status codes to return, in order. The last one repeats. */
    @Volatile private var statuses = listOf(200)
    @Volatile private var delayMillis = 0L
    private val calls = AtomicInteger()

    private val device = DeviceContext("Acme", "Pixel Test", "15", 35, "0.2.0")
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val url get() = "http://127.0.0.1:${server.address.port}/hook"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val n = calls.getAndIncrement()
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        received += Received(
            headers = listOf("Content-Type", WebhookSink.SIGNATURE_HEADER, "Authorization")
                .associateWith { exchange.requestHeaders.getFirst(it) },
            body = body,
        )
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val status = statuses[minOf(n, statuses.lastIndex)]
        if (status in 300..399) exchange.responseHeaders.add("Location", "http://127.0.0.1:1/elsewhere")
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun sink(configure: WebhookSink.Builder.() -> Unit = {}): WebhookSink =
        WebhookSink.Builder(url).allowInsecureHttp().clock(clock).apply(configure).build()

    private fun telemetry(sink: TelemetrySink): PosGuardTelemetry {
        var id = 0
        return PosGuardTelemetry.Builder(device).addSink(sink).clock(clock).idGenerator { "evt-${++id}" }.build()
    }

    private fun event(id: String = "evt-1", message: String? = "hello") =
        TelemetryEvent(eventId = id, type = EventType.APP_EVENT, timestamp = now, device = device, message = message)

    @Test
    fun `posts the v1 envelope with scrubbed events`() = runBlocking {
        val telemetry = telemetry(sink())
        telemetry.record(EventType.APP_ERROR, "Decline for card 4111111111111111", mapOf("lane" to "3"))
        telemetry.record(EventType.APP_EVENT, "drawer opened")

        assertTrue(telemetry.flush())

        assertEquals(1, received.size)
        val request = received.single()
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertFalse("raw PAN must never reach the webhook", request.body.contains("4111111111111111"))
        val json = JSONObject(request.body)
        assertEquals(1, json.getInt("schemaVersion"))
        val events = json.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("evt-1", events.getJSONObject(0).getString("eventId"))
        assertEquals("Decline for card [REDACTED_PAN]", events.getJSONObject(0).getString("message"))
        assertEquals("3", events.getJSONObject(0).getJSONObject("attributes").getString("lane"))
        assertEquals("drawer opened", events.getJSONObject(1).getString("message"))
    }

    @Test
    fun `signature verifies with the shared secret`() = runBlocking {
        sink { signingSecret("test-secret") }.send(listOf(event()))

        val request = received.single()
        val header = request.headers[WebhookSink.SIGNATURE_HEADER]!!
        val parts = header.split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals(now.epochSecond.toString(), parts["t"])

        // Receiver-side check, written independently of WebhookSink.
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("test-secret".toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal("${parts["t"]}.${request.body}".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, parts["v1"])
    }

    @Test
    fun `no signature header without a secret`() = runBlocking {
        sink().send(listOf(event()))
        assertNull(received.single().headers[WebhookSink.SIGNATURE_HEADER])
    }

    @Test
    fun `custom headers are sent`() = runBlocking {
        sink { header("Authorization", "Bearer test-token") }.send(listOf(event()))
        assertEquals("Bearer test-token", received.single().headers["Authorization"])
    }

    @Test
    fun `empty batch makes no request`() = runBlocking {
        sink().send(emptyList())
        assertEquals(0, calls.get())
    }

    @Test
    fun `server error throws and telemetry keeps the batch for the next flush`() = runBlocking {
        statuses = listOf(500, 200)
        val telemetry = telemetry(sink())
        telemetry.record(EventType.APP_EVENT, "first")

        assertFalse(telemetry.flush())
        assertEquals(1, telemetry.bufferedCount)

        assertTrue(telemetry.flush())
        assertEquals(0, telemetry.bufferedCount)
        assertEquals(2, received.size)
        assertEquals(received[0].body, received[1].body) // same eventId, receiver can deduplicate
    }

    @Test
    fun `status code is reported on failure`() {
        statuses = listOf(503)
        val e = assertThrows(WebhookDeliveryException::class.java) { runBlocking { sink().send(listOf(event())) } }
        assertEquals(503, e.statusCode)
    }

    @Test
    fun `redirects are failures and are not followed`() {
        statuses = listOf(302)
        val e = assertThrows(WebhookDeliveryException::class.java) { runBlocking { sink().send(listOf(event())) } }
        assertEquals(302, e.statusCode)
        assertEquals(1, calls.get())
    }

    @Test
    fun `timeout throws without a status code`() {
        delayMillis = 1_500
        val e = assertThrows(WebhookDeliveryException::class.java) {
            runBlocking { sink { timeouts(connectMillis = 1_000, readMillis = 200) }.send(listOf(event())) }
        }
        assertNull(e.statusCode)
    }

    @Test
    fun `unreachable host throws`() {
        server.stop(0)
        val e = assertThrows(WebhookDeliveryException::class.java) { runBlocking { sink().send(listOf(event())) } }
        assertNull(e.statusCode)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() } // for @After
    }

    @Test
    fun `http is rejected unless explicitly allowed`() {
        assertThrows(IllegalArgumentException::class.java) { WebhookSink.Builder("http://example.com/hook").build() }
        WebhookSink.Builder("http://example.com/hook").allowInsecureHttp().build()
        WebhookSink.Builder("https://example.com/hook").build()
    }

    @Test
    fun `builder rejects invalid input`() {
        assertThrows(IllegalArgumentException::class.java) { WebhookSink.Builder("not a url").build() }
        assertThrows(IllegalArgumentException::class.java) { WebhookSink.Builder("ftp://example.com/x").build() }
        assertThrows(IllegalArgumentException::class.java) { WebhookSink.Builder("https:///nohost").build() }
        val b = WebhookSink.Builder("https://example.com/hook")
        assertThrows(IllegalArgumentException::class.java) { b.signingSecret("") }
        assertThrows(IllegalArgumentException::class.java) { b.header("X-Test", "a\r\nInjected: 1") }
        assertThrows(IllegalArgumentException::class.java) { b.header("Bad Name", "v") }
        assertThrows(IllegalArgumentException::class.java) { b.header("content-type", "text/plain") }
        assertThrows(IllegalArgumentException::class.java) { b.header(WebhookSink.SIGNATURE_HEADER, "forged") }
        assertThrows(IllegalArgumentException::class.java) { b.timeouts(0, 100) }
    }
}
