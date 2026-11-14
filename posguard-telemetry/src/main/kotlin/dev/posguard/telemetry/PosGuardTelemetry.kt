package dev.posguard.telemetry

import dev.posguard.core.event.GuardEvent
import dev.posguard.core.event.GuardEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives telemetry batches. This is the extension point for dashboards,
 * webhooks (V2), and private products. Events arrive already scrubbed.
 * Throw to signal failure: the batch stays buffered and is retried.
 */
public fun interface TelemetrySink {
    public suspend fun send(events: List<TelemetryEvent>)
}

/**
 * Secure telemetry for posguard. Register it as a [GuardEventListener] on
 * `PosGuard.Builder` and `PosGuardLedger.Builder`, and call [record] for app
 * errors.
 *
 * - Every event is scrubbed **before** it is buffered, so raw data is never
 *   held or sent.
 * - The buffer is bounded. When full, the oldest events are dropped and
 *   counted in [droppedCount].
 * - Events leave the buffer only after **every** sink accepted the batch. When
 *   a sink fails, the batch is retried on the next flush, so sinks that already
 *   succeeded may see it again. They can drop duplicates by `eventId`.
 */
public class PosGuardTelemetry private constructor(
    private val device: DeviceContext,
    private val sinks: List<TelemetrySink>,
    private val scrubber: Scrubber,
    private val maxBufferedEvents: Int,
    private val batchSize: Int,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val newId: () -> String,
) : GuardEventListener {

    private val lock = Any()
    private val buffer = ArrayDeque<TelemetryEvent>()
    private val flushLock = Mutex()
    private val dropped = AtomicLong()

    /** Events dropped because the buffer was full. */
    public val droppedCount: Long get() = dropped.get()

    /** Events waiting to be delivered. */
    public val bufferedCount: Int get() = synchronized(lock) { buffer.size }

    /** Non-blocking: maps, scrubs, and buffers the event. */
    override fun onEvent(event: GuardEvent) {
        enqueue(TelemetryEvent.from(event, device, newId()))
    }

    /**
     * Records an app error or event. [message] and [attributes] are scrubbed,
     * but you should still never pass card data, PINs, or personal data.
     */
    public fun record(type: EventType, message: String? = null, attributes: Map<String, String> = emptyMap()) {
        require(type == EventType.APP_ERROR || type == EventType.APP_EVENT) {
            "record() is for APP_ERROR and APP_EVENT. posguard events arrive through onEvent()."
        }
        enqueue(
            TelemetryEvent(
                eventId = newId(),
                type = type,
                timestamp = clock.instant(),
                device = device,
                message = message,
                attributes = attributes,
            ),
        )
    }

    /**
     * Delivers buffered events to every sink, in batches. Returns true when the
     * buffer was fully drained, or false if a sink failed (events are kept).
     */
    public suspend fun flush(): Boolean = flushLock.withLock { drain() }

    private suspend fun drain(): Boolean {
        while (true) {
            val batch = synchronized(lock) { buffer.take(batchSize) }
            if (batch.isEmpty()) return true
            // Every sink gets the batch, even if an earlier one failed.
            val results = sinks.map { sink -> deliver(sink, batch) }
            if (!results.all { it }) return false
            val sent = batch.mapTo(HashSet()) { it.eventId }
            synchronized(lock) { buffer.removeAll { it.eventId in sent } }
        }
    }

    private suspend fun deliver(sink: TelemetrySink, batch: List<TelemetryEvent>): Boolean = try {
        sink.send(batch)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun enqueue(raw: TelemetryEvent) {
        val event = raw.scrubbed(scrubber)
        val shouldFlush = synchronized(lock) {
            buffer.addLast(event)
            while (buffer.size > maxBufferedEvents) {
                buffer.removeFirst()
                dropped.incrementAndGet()
            }
            buffer.size >= batchSize
        }
        if (shouldFlush) scope.launch { flush() }
    }

    /** Plain builder. No DI framework needed. */
    public class Builder(private val device: DeviceContext) {
        private val sinks = mutableListOf<TelemetrySink>()
        private var scrubber: Scrubber = Scrubber.Default
        private var maxBufferedEvents: Int = DEFAULT_MAX_BUFFERED_EVENTS
        private var batchSize: Int = DEFAULT_BATCH_SIZE
        private var scope: CoroutineScope? = null
        private var clock: Clock = Clock.systemUTC()
        private var idGenerator: () -> String = { UUID.randomUUID().toString() }

        public fun addSink(sink: TelemetrySink): Builder = apply { sinks += sink }

        /** Defaults to [Scrubber.Default]. */
        public fun scrubber(scrubber: Scrubber): Builder = apply { this.scrubber = scrubber }

        /** Buffer cap. When full, the oldest events are dropped. Defaults to 500. */
        public fun maxBufferedEvents(max: Int): Builder = apply {
            require(max > 0) { "maxBufferedEvents must be positive." }
            maxBufferedEvents = max
        }

        /** Events per sink call, and the buffer size that triggers an automatic flush. Defaults to 20. */
        public fun batchSize(size: Int): Builder = apply {
            require(size > 0) { "batchSize must be positive." }
            batchSize = size
        }

        /** Scope for automatic flushes. Defaults to `SupervisorJob() + Dispatchers.IO`. */
        public fun scope(scope: CoroutineScope): Builder = apply { this.scope = scope }

        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Test hook for deterministic ids. */
        internal fun idGenerator(generator: () -> String): Builder = apply { idGenerator = generator }

        public fun build(): PosGuardTelemetry {
            require(batchSize <= maxBufferedEvents) { "batchSize must not exceed maxBufferedEvents." }
            return PosGuardTelemetry(
                device = device,
                sinks = sinks.toList(),
                scrubber = scrubber,
                maxBufferedEvents = maxBufferedEvents,
                batchSize = batchSize,
                scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO),
                clock = clock,
                newId = idGenerator,
            )
        }
    }

    public companion object {
        public const val DEFAULT_MAX_BUFFERED_EVENTS: Int = 500
        public const val DEFAULT_BATCH_SIZE: Int = 20
    }
}
