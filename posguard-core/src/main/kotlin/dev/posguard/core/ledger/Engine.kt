package dev.posguard.core.ledger

/**
 * Result reported by the payment engine for a submission. It carries only the
 * engine's opaque reference and a non-sensitive decline code, never card data.
 */
public sealed interface EngineResult {
    public data class Authorized(
        val engineReference: String,
        /**
         * Set when the engine approved **less** than requested (a partial approval,
         * for example when an EBT SNAP balance is too low). Same currency as the
         * request, positive, and not more than the requested amount. Null means the
         * full amount was approved.
         */
        val approvedAmount: Money? = null,
    ) : EngineResult

    public data class Declined(
        val engineReference: String? = null,
        /** Engine or processor decline code, for example `"05"`. Must not contain card data. */
        val declineCode: String? = null,
    ) : EngineResult

    /** The engine did not return a result (timeout, connection lost after submit, crash). */
    public data object Unknown : EngineResult
}

/** Answer to a status query by [IdempotencyKey]. */
public sealed interface EngineStatus {
    /** The engine processed and approved it. */
    public data class Authorized(
        val engineReference: String,
        /** As in [EngineResult.Authorized.approvedAmount]. */
        val approvedAmount: Money? = null,
    ) : EngineStatus

    /** The engine processed and declined it. */
    public data class Declined(val engineReference: String? = null, val declineCode: String? = null) : EngineStatus

    /** The engine confirms it never processed this key, so resubmitting with the same key is safe. */
    public data object NotProcessed : EngineStatus

    /** The engine could not be reached. Nothing is known, so do not resubmit. */
    public data object Unreachable : EngineStatus
}

/**
 * Looks up the outcome of an earlier submission by its idempotency key.
 * Implemented by the adapter around the certified payment engine (and by
 * `mock-payment-engine` for demos). Used by ledger recovery.
 */
public fun interface EngineStatusSource {
    public suspend fun queryStatus(key: IdempotencyKey): EngineStatus
}

/**
 * Combines status sources for apps whose legs go to different engines (for
 * example, EBT legs to an EBT-capable engine and card legs to a card engine),
 * so one ledger can recover all of them.
 *
 * - Any source that knows the key (Authorized or Declined) wins.
 * - Otherwise, if any source is unreachable (or throws), the answer is
 *   [EngineStatus.Unreachable]: that engine might have processed it.
 * - [EngineStatus.NotProcessed] only when **every** source says so. Only then
 *   is resubmitting or voiding safe.
 *
 * Sources are queried in order. Idempotency keys are unique per transaction,
 * so at most one engine should know a key.
 */
public fun combinedStatusSource(vararg sources: EngineStatusSource): EngineStatusSource {
    require(sources.isNotEmpty()) { "At least one status source is required." }
    val all = sources.toList()
    return EngineStatusSource { key ->
        var unreachable = false
        for (source in all) {
            val status = try {
                source.queryStatus(key)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                EngineStatus.Unreachable
            }
            when (status) {
                is EngineStatus.Authorized, is EngineStatus.Declined -> return@EngineStatusSource status
                EngineStatus.Unreachable -> unreachable = true
                EngineStatus.NotProcessed -> Unit
            }
        }
        if (unreachable) EngineStatus.Unreachable else EngineStatus.NotProcessed
    }
}
