package dev.posguard.mockengine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side memory of the simulated processor: which idempotency keys it has
 * processed. A real processor keeps this on its servers. The demo keeps it on
 * the device so it survives the app being killed (scenario 5).
 *
 * Methods do blocking I/O. [MockPaymentEngine] calls them off the main thread.
 */
public interface MockProcessorStore {
    public fun get(idempotencyKey: String): ProcessedCharge?
    public fun put(charge: ProcessedCharge)
    public fun all(): List<ProcessedCharge>
    public fun clear()
}

/** In-memory store for tests. Lost when the process dies. */
public class InMemoryMockProcessorStore : MockProcessorStore {
    private val charges = ConcurrentHashMap<String, ProcessedCharge>()
    override fun get(idempotencyKey: String): ProcessedCharge? = charges[idempotencyKey]
    override fun put(charge: ProcessedCharge) {
        charges[charge.idempotencyKey] = charge
    }
    override fun all(): List<ProcessedCharge> = charges.values.toList()
    override fun clear(): Unit = charges.clear()
}

/**
 * SharedPreferences-backed store for the demo app. Writes use `commit()`, so a
 * charge is on disk before the engine returns, even if the app is killed right
 * after.
 */
public class SharedPreferencesMockProcessorStore(
    context: Context,
    name: String = DEFAULT_NAME,
) : MockProcessorStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(idempotencyKey: String): ProcessedCharge? =
        prefs.getString(idempotencyKey, null)?.let { decode(idempotencyKey, it) }

    override fun put(charge: ProcessedCharge) {
        check(prefs.edit().putString(charge.idempotencyKey, encode(charge)).commit()) {
            "Mock processor store write failed."
        }
    }

    override fun all(): List<ProcessedCharge> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { decode(key, it) } }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private fun encode(c: ProcessedCharge): String =
        listOf(if (c.approved) "A" else "D", c.engineReference, c.amountCents, c.currency, c.declineCode.orEmpty())
            .joinToString(SEPARATOR)

    private fun decode(key: String, raw: String): ProcessedCharge? {
        val parts = raw.split(SEPARATOR)
        if (parts.size != FIELD_COUNT) return null
        return ProcessedCharge(
            idempotencyKey = key,
            approved = parts[0] == "A",
            engineReference = parts[1],
            amountCents = parts[2].toLongOrNull() ?: return null,
            currency = parts[3],
            declineCode = parts[4].ifEmpty { null },
        )
    }

    public companion object {
        public const val DEFAULT_NAME: String = "posguard-mock-processor"
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 5
    }
}
