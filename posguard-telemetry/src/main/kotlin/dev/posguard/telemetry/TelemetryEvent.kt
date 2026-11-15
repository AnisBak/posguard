package dev.posguard.telemetry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.posguard.core.event.GuardEvent
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.ReasonCode
import java.time.Instant

/** Telemetry event types (schema v1). Adding a type is a minor-version change. */
public enum class EventType {
    PREFLIGHT_EVALUATED,
    TXN_STATE_CHANGED,
    APP_ERROR,
    APP_EVENT,

    /** Experimental relay-risk rules flagged a payment for review. Attributes: `txnId`, `rules`. */
    RELAY_RISK_FLAGGED,

    /** The merchant reviewed a flagged payment. Attributes: `txnId`. */
    RELAY_RISK_REVIEWED,
}

/** A ledger state change. Ids are opaque, and no amounts or engine references are included. */
public data class TxnTransition(val txnId: String, val from: String?, val to: String)

/** Device and app context attached to every event. */
public data class DeviceContext(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersion: String,
) {
    public companion object {
        /** Reads [Build] and the app's version name. Never throws. */
        public fun fromSystem(context: Context): DeviceContext = DeviceContext(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = appVersion(context),
        )

        private fun appVersion(context: Context): String = try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // PackageInfoFlags overload: API 33+.
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: UNKNOWN
        } catch (_: Exception) {
            UNKNOWN
        }

        private const val UNKNOWN = "unknown"
    }
}

/**
 * One telemetry event (schema v1, see docs/event-schema.md). Events reach
 * sinks only after [scrubbed].
 */
public data class TelemetryEvent(
    val eventId: String,
    val type: EventType,
    val timestamp: Instant,
    val device: DeviceContext,
    val verdict: GuardAction? = null,
    val reasonCodes: List<String> = emptyList(),
    val txn: TxnTransition? = null,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    val schemaVersion: Int get() = SCHEMA_VERSION

    /** Returns a copy with every string field run through [scrubber]. */
    public fun scrubbed(scrubber: Scrubber): TelemetryEvent = copy(
        eventId = scrubber.scrub(eventId),
        reasonCodes = reasonCodes.map(scrubber::scrub),
        txn = txn?.let { TxnTransition(scrubber.scrub(it.txnId), it.from?.let(scrubber::scrub), scrubber.scrub(it.to)) },
        device = device.copy(
            manufacturer = scrubber.scrub(device.manufacturer),
            model = scrubber.scrub(device.model),
            osVersion = scrubber.scrub(device.osVersion),
            appVersion = scrubber.scrub(device.appVersion),
        ),
        message = message?.let(scrubber::scrub),
        attributes = scrubber.scrubAttributes(attributes),
    )

    /** Compact JSON (schema v1). */
    public fun toJson(): String = Json.obj {
        field("schemaVersion", schemaVersion)
        field("eventId", eventId)
        field("type", type.name)
        field("timestamp", timestamp.toString())
        field("verdict", verdict?.name)
        array("reasonCodes", reasonCodes)
        if (txn == null) {
            nullField("txn")
        } else {
            obj("txn") {
                field("txnId", txn.txnId)
                field("from", txn.from)
                field("to", txn.to)
            }
        }
        obj("device") {
            field("manufacturer", device.manufacturer)
            field("model", device.model)
            field("osVersion", device.osVersion)
            field("sdkInt", device.sdkInt)
            field("appVersion", device.appVersion)
        }
        field("message", message)
        obj("attributes") { attributes.forEach { (k, v) -> field(k, v) } }
    }

    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        /** Maps a core [GuardEvent] to a telemetry event. Signal details are not included. */
        public fun from(event: GuardEvent, device: DeviceContext, eventId: String): TelemetryEvent = when (event) {
            is GuardEvent.PreflightEvaluated -> TelemetryEvent(
                eventId = eventId,
                type = EventType.PREFLIGHT_EVALUATED,
                timestamp = event.at,
                device = device,
                verdict = event.verdict.action,
                reasonCodes = event.verdict.reasons.map { it.code },
            )
            is GuardEvent.TxnStateChanged -> TelemetryEvent(
                eventId = eventId,
                type = EventType.TXN_STATE_CHANGED,
                timestamp = event.at,
                device = device,
                txn = TxnTransition(event.id.value, event.from?.name, event.to.name),
            )
            is GuardEvent.RelayRiskFlagged -> TelemetryEvent(
                eventId = eventId,
                type = EventType.RELAY_RISK_FLAGGED,
                timestamp = event.at,
                device = device,
                reasonCodes = listOf(ReasonCode.RELAY_RISK_PATTERN.code),
                attributes = mapOf("txnId" to event.id.value, "rules" to event.ruleIds.joinToString(",")),
            )
            is GuardEvent.RelayRiskReviewed -> TelemetryEvent(
                eventId = eventId,
                type = EventType.RELAY_RISK_REVIEWED,
                timestamp = event.at,
                device = device,
                attributes = mapOf("txnId" to event.id.value),
            )
        }
    }
}

/** Minimal JSON writer: objects, string/number/null fields, and string arrays. */
internal object Json {
    fun obj(block: ObjectWriter.() -> Unit): String = ObjectWriter(StringBuilder()).apply(block).close()

    class ObjectWriter(private val sb: StringBuilder) {
        private var first = true

        init {
            sb.append('{')
        }

        private fun key(name: String) {
            if (!first) sb.append(',')
            first = false
            quote(name, sb)
            sb.append(':')
        }

        fun field(name: String, value: String?) {
            key(name)
            if (value == null) sb.append("null") else quote(value, sb)
        }

        fun field(name: String, value: Int) {
            key(name)
            sb.append(value)
        }

        fun nullField(name: String) {
            key(name)
            sb.append("null")
        }

        fun array(name: String, values: List<String>) {
            key(name)
            sb.append('[')
            values.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                quote(v, sb)
            }
            sb.append(']')
        }

        fun obj(name: String, block: ObjectWriter.() -> Unit) {
            key(name)
            ObjectWriter(sb).apply(block).close()
        }

        fun close(): String {
            sb.append('}')
            return sb.toString()
        }
    }

    fun quote(value: String, sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
