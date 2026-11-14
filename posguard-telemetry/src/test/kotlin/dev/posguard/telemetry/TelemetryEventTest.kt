package dev.posguard.telemetry

import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.event.GuardEvent
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.core.verdict.ReasonCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class TelemetryEventTest {

    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val device = DeviceContext("Acme", "Pixel Test", "15", 35, "0.1.0")

    @Test
    fun `preflight event maps verdict and reason codes without signal details`() {
        val verdict = GuardVerdict.Block(
            reasons = listOf(ReasonCode.SCREEN_OVERLAY_PRESENT, ReasonCode.INTEGRITY_UNAVAILABLE),
            signals = listOf(SignalResult.Detected("overlay", ReasonCode.SCREEN_OVERLAY_PRESENT, "pkg=com.example")),
            evaluatedAt = now,
        )
        val event = TelemetryEvent.from(GuardEvent.PreflightEvaluated(verdict), device, "e-1")

        assertEquals(EventType.PREFLIGHT_EVALUATED, event.type)
        assertEquals(GuardAction.BLOCK, event.verdict)
        assertEquals(listOf("SCREEN_OVERLAY_PRESENT", "INTEGRITY_UNAVAILABLE"), event.reasonCodes)
        assertEquals(now, event.timestamp)
        assertFalse(event.toJson().contains("pkg=com.example"))
    }

    @Test
    fun `txn event maps transition`() {
        val event = TelemetryEvent.from(
            GuardEvent.TxnStateChanged(TxnId("t-1"), TxnState.SUBMITTED, TxnState.UNKNOWN, now),
            device,
            "e-2",
        )
        assertEquals(EventType.TXN_STATE_CHANGED, event.type)
        assertEquals(TxnTransition("t-1", "SUBMITTED", "UNKNOWN"), event.txn)
    }

    @Test
    fun `json has every schema field and parses`() {
        val event = TelemetryEvent(
            eventId = "e-3",
            type = EventType.TXN_STATE_CHANGED,
            timestamp = now,
            device = device,
            txn = TxnTransition("t-1", null, "CREATED"),
            attributes = mapOf("lane" to "3"),
        )
        val json = JSONObject(event.toJson())

        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("e-3", json.getString("eventId"))
        assertEquals("TXN_STATE_CHANGED", json.getString("type"))
        assertEquals("2026-11-14T12:00:00Z", json.getString("timestamp"))
        assertTrue(json.isNull("verdict"))
        assertEquals(0, json.getJSONArray("reasonCodes").length())
        assertTrue(json.getJSONObject("txn").isNull("from"))
        assertEquals("CREATED", json.getJSONObject("txn").getString("to"))
        assertEquals(35, json.getJSONObject("device").getInt("sdkInt"))
        assertEquals("Pixel Test", json.getJSONObject("device").getString("model"))
        assertTrue(json.isNull("message"))
        assertEquals("3", json.getJSONObject("attributes").getString("lane"))
    }

    @Test
    fun `json escapes quotes, backslashes, control characters, and unicode`() {
        val tricky = "quote\" back\\slash\nnew\ttab\u0001ctl   café 😀"
        val event = TelemetryEvent(
            eventId = "e-4", type = EventType.APP_ERROR, timestamp = now, device = device,
            message = tricky, attributes = mapOf("k\"ey" to tricky),
        )
        val raw = event.toJson()
        assertFalse(raw.contains('\n'))
        val json = JSONObject(raw)
        assertEquals(tricky, json.getString("message"))
        assertEquals(tricky, json.getJSONObject("attributes").getString("k\"ey"))
    }

    @Test
    fun `scrubbed covers message, attributes, device, and reason codes`() {
        val event = TelemetryEvent(
            eventId = "550e8400-e29b-41d4-a716-446655440000",
            type = EventType.APP_ERROR,
            timestamp = now,
            device = device.copy(model = "owner jane@example.com"),
            reasonCodes = listOf("4111111111111111"),
            txn = TxnTransition("550e8400-e29b-41d4-a716-446655440001", "SUBMITTED", "UNKNOWN"),
            message = "failed for 4111 1111 1111 1111",
            attributes = mapOf("cvv" to "123", "note" to "call 415-555-0123"),
        ).scrubbed(Scrubber.Default)

        assertEquals("550e8400-e29b-41d4-a716-446655440000", event.eventId)
        assertEquals("550e8400-e29b-41d4-a716-446655440001", event.txn!!.txnId)
        assertEquals("failed for ${ScrubRules.PAN_PLACEHOLDER}", event.message)
        assertEquals(listOf(ScrubRules.PAN_PLACEHOLDER), event.reasonCodes)
        assertEquals("owner ${ScrubRules.EMAIL_PLACEHOLDER}", event.device.model)
        assertEquals(Scrubber.REDACTED, event.attributes["cvv"])
        assertEquals("call ${ScrubRules.PHONE_PLACEHOLDER}", event.attributes["note"])
    }

    @Test
    fun `device context from system never throws`() {
        val ctx = DeviceContext.fromSystem(ApplicationProvider.getApplicationContext())
        assertTrue(ctx.sdkInt > 0)
        assertTrue(ctx.appVersion.isNotEmpty())
    }
}
