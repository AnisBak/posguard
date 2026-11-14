package dev.posguard.core.ledger

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Everything the journal stores about a transaction. The fields are limited on
 * purpose (CLAUDE.md §7): **no card numbers, PINs, track data, or CVV**, and
 * there is no field where they could go.
 */
public data class TransactionRecord(
    val id: TxnId,
    val idempotencyKey: IdempotencyKey,
    val amount: Money,
    val tender: Tender,
    val state: TxnState,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Opaque reference from the engine, once known. */
    val engineReference: String? = null,
)

/** What recovery concluded about one pending transaction. */
public enum class RecoveryOutcome {
    /** The engine reported a final result, and the record now holds it. */
    RESOLVED,

    /**
     * The engine confirmed it never processed this key. The app may resubmit
     * **with the same idempotency key**, or void the transaction.
     */
    SAFE_TO_RESUBMIT,

    /**
     * The engine could not be reached. The transaction stays pending. Do not
     * resubmit, and retry recovery when connectivity returns.
     */
    UNRESOLVED,
}

/** One transaction examined by [TransactionLedger.recoverPending]. */
public data class RecoveredTxn(
    val previousState: TxnState,
    val record: TransactionRecord,
    val outcome: RecoveryOutcome,
)

/**
 * Outage-safe transaction journal. Its goal is that a payment is **never lost
 * and never charged twice**, even if the app crashes or the network drops
 * mid-payment.
 *
 * Typical flow:
 * 1. [recoverPending] on app start and on reconnect. Resolve pending payments before new ones.
 * 2. [begin] records the payment and creates its idempotency key.
 * 3. [markSubmitted] is called just before handing the payment to the engine (with the key).
 * 4. [markResult] records the engine's answer. [EngineResult.Unknown] moves it to [TxnState.UNKNOWN].
 * 5. [markCompleted] or [markVoided] closes it.
 *
 * Every state change is checked against [TxnStateMachine]. A forbidden
 * transition throws [IllegalTxnTransitionException]. Implementations must be
 * main-safe and persist each change before returning.
 */
public interface TransactionLedger {
    /** Records a new payment in [TxnState.CREATED] with a new idempotency key. */
    public suspend fun begin(amount: Money, tender: Tender): TxnId

    /** CREATED → SUBMITTED, or UNKNOWN → SUBMITTED after recovery returned [RecoveryOutcome.SAFE_TO_RESUBMIT]. */
    public suspend fun markSubmitted(id: TxnId)

    /** SUBMITTED → AUTHORIZED, DECLINED, or UNKNOWN. UNKNOWN → AUTHORIZED or DECLINED. */
    public suspend fun markResult(id: TxnId, result: EngineResult)

    /** AUTHORIZED or DECLINED → COMPLETED. */
    public suspend fun markCompleted(id: TxnId)

    /** CREATED, UNKNOWN (engine confirmed not processed), or AUTHORIZED → VOIDED. */
    public suspend fun markVoided(id: TxnId)

    /**
     * Resolves every transaction whose state [TxnState.needsRecovery] by querying
     * the engine with its idempotency key. Call on app start and on reconnect.
     */
    public suspend fun recoverPending(): List<RecoveredTxn>

    /** The current record, or null if [id] is unknown. */
    public suspend fun get(id: TxnId): TransactionRecord?

    /** Emits the current state of [id], then every change. */
    public fun observe(id: TxnId): Flow<TxnState>

    /** Emits the [limit] most recent transactions (newest first), then again after every change. */
    public fun observeRecent(limit: Int): Flow<List<TransactionRecord>>
}
