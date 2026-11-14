package dev.posguard.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps the most recent events in memory. For tests and the demo's event panel. */
public class InMemorySink(private val capacity: Int = DEFAULT_CAPACITY) : TelemetrySink {
    init {
        require(capacity > 0) { "capacity must be positive." }
    }

    private val state = MutableStateFlow<List<TelemetryEvent>>(emptyList())

    /** Most recent events, oldest first. */
    public val events: StateFlow<List<TelemetryEvent>> = state.asStateFlow()

    override suspend fun send(events: List<TelemetryEvent>) {
        state.update { (it + events).takeLast(capacity) }
    }

    public fun clear() {
        state.value = emptyList()
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 200
    }
}

/**
 * Appends events as JSON lines to app-private storage, with size-based
 * rotation. Total disk use is at most about `maxFileBytes * maxFiles` (one
 * oversized batch can exceed a single file's limit).
 *
 * Pass a directory inside `Context.getFilesDir()` or `getNoBackupFilesDir()`.
 */
public class JsonLinesFileSink(
    private val directory: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) : TelemetrySink {

    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive." }
        require(maxFiles >= 1) { "maxFiles must be at least 1." }
    }

    private val lock = Mutex()

    override suspend fun send(events: List<TelemetryEvent>) {
        if (events.isEmpty()) return
        val payload = events.joinToString(separator = "\n", postfix = "\n") { it.toJson() }.toByteArray()
        lock.withLock {
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                val current = file(0)
                if (current.exists() && current.length() > 0 && current.length() + payload.size > maxFileBytes) {
                    rotate()
                }
                file(0).appendBytes(payload)
            }
        }
    }

    /** Existing log files, oldest first. */
    public fun files(): List<File> = (maxFiles - 1 downTo 0).map(::file).filter { it.exists() }

    private fun rotate() {
        file(maxFiles - 1).delete()
        for (i in maxFiles - 2 downTo 0) {
            val f = file(i)
            if (f.exists()) f.renameTo(file(i + 1))
        }
    }

    private fun file(index: Int): File =
        File(directory, if (index == 0) "$BASE_NAME.jsonl" else "$BASE_NAME.$index.jsonl")

    public companion object {
        public const val DEFAULT_MAX_FILE_BYTES: Long = 256L * 1024
        public const val DEFAULT_MAX_FILES: Int = 2
        private const val BASE_NAME = "posguard-events"
    }
}
