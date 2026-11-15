package dev.posguard.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sends telemetry batches to an HTTPS endpoint the integrator controls, for
 * example a merchant's existing monitoring tool.
 *
 * - Each [send] makes one `POST` with body `{"schemaVersion":1,"events":[...]}`.
 *   The events are the v1 JSON from [TelemetryEvent.toJson], already scrubbed by
 *   [PosGuardTelemetry].
 * - Any 2xx response is success. Any other status (redirects included, which
 *   are never followed), a timeout, or an I/O error throws
 *   [WebhookDeliveryException], so [PosGuardTelemetry] keeps the batch and
 *   retries it on the next flush. Receivers should drop duplicates by `eventId`.
 * - With a signing secret, each request carries
 *   `X-Posguard-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<body>")>`.
 *   See `docs/event-schema.md` for how to verify it.
 *
 * The URL and secret come from the integrating app's own config. None ship
 * with posguard. The app must declare the `android.permission.INTERNET`
 * permission. This library does not add it.
 */
public class WebhookSink private constructor(
    private val url: URL,
    private val signingKey: ByteArray?,
    private val headers: Map<String, String>,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val clock: Clock,
) : TelemetrySink {

    override suspend fun send(events: List<TelemetryEvent>) {
        if (events.isEmpty()) return
        val body = envelope(events).toByteArray(Charsets.UTF_8)
        withContext(Dispatchers.IO) { post(body) }
    }

    private fun post(body: ByteArray) {
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw WebhookDeliveryException("Could not open connection.", cause = e)
        }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", CONTENT_TYPE)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            signingKey?.let { key ->
                connection.setRequestProperty(SIGNATURE_HEADER, signatureHeader(key, clock.instant().epochSecond, body))
            }
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            // Read and discard the response so the connection can be reused.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.use { it.readBytes() }
            if (status !in 200..299) {
                throw WebhookDeliveryException("Webhook responded with HTTP $status.", statusCode = status)
            }
        } catch (e: WebhookDeliveryException) {
            throw e
        } catch (e: IOException) {
            throw WebhookDeliveryException("Webhook delivery failed: ${e.javaClass.simpleName}.", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    /** Builder. [url] must be `https://` unless [allowInsecureHttp] is called. */
    public class Builder(private val url: String) {
        private var secret: ByteArray? = null
        private val headers = linkedMapOf<String, String>()
        private var connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS
        private var readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS
        private var allowHttp = false
        private var clock: Clock = Clock.systemUTC()

        /** Signs every request with HMAC-SHA256. Load the secret from untracked config, never from source. */
        public fun signingSecret(secret: String): Builder = apply {
            require(secret.isNotEmpty()) { "signingSecret must not be empty." }
            this.secret = secret.toByteArray(Charsets.UTF_8)
        }

        /** Adds a request header, for example `Authorization`. */
        public fun header(name: String, value: String): Builder = apply {
            require(name.isNotBlank() && name.none { it.isWhitespace() || it == ':' || it.isISOControl() }) {
                "Invalid header name."
            }
            require(value.none { it == '\r' || it == '\n' }) { "Header values must not contain line breaks." }
            require(RESERVED_HEADERS.none { it.equals(name, ignoreCase = true) }) { "Header $name is set by WebhookSink." }
            headers[name] = value
        }

        /** Defaults: connect 5 s, read 10 s. */
        public fun timeouts(connectMillis: Int, readMillis: Int): Builder = apply {
            require(connectMillis > 0 && readMillis > 0) { "Timeouts must be positive." }
            connectTimeoutMillis = connectMillis
            readTimeoutMillis = readMillis
        }

        /** Allows `http://` URLs. **For local development only**: events would travel unencrypted. */
        public fun allowInsecureHttp(): Builder = apply { allowHttp = true }

        /** Test hook for the signature timestamp. */
        internal fun clock(clock: Clock): Builder = apply { this.clock = clock }

        public fun build(): WebhookSink {
            val parsed = try {
                URL(url)
            } catch (e: java.net.MalformedURLException) {
                throw IllegalArgumentException("Invalid webhook URL.", e)
            }
            require(!parsed.host.isNullOrEmpty()) { "Webhook URL needs a host." }
            when (parsed.protocol) {
                "https" -> Unit
                "http" -> require(allowHttp) { "Webhook URL must use https (call allowInsecureHttp() for local development)." }
                else -> throw IllegalArgumentException("Webhook URL must use https.")
            }
            return WebhookSink(parsed, secret, headers.toMap(), connectTimeoutMillis, readTimeoutMillis, clock)
        }
    }

    public companion object {
        public const val SIGNATURE_HEADER: String = "X-Posguard-Signature"
        public const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 5_000
        public const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 10_000
        private const val CONTENT_TYPE = "application/json; charset=utf-8"
        private val RESERVED_HEADERS = listOf("Content-Type", "Content-Length", SIGNATURE_HEADER)

        internal fun envelope(events: List<TelemetryEvent>): String =
            events.joinToString(
                separator = ",",
                prefix = "{\"schemaVersion\":${TelemetryEvent.SCHEMA_VERSION},\"events\":[",
                postfix = "]}",
            ) { it.toJson() }

        internal fun signatureHeader(key: ByteArray, epochSeconds: Long, body: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.update("$epochSeconds.".toByteArray(Charsets.UTF_8))
            val digest = mac.doFinal(body)
            return "t=$epochSeconds,v1=" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** A webhook batch was not accepted. [statusCode] is null when no HTTP response arrived. */
public class WebhookDeliveryException(
    message: String,
    public val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
