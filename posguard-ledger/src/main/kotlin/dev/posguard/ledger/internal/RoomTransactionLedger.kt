package dev.posguard.ledger.internal

import androidx.room.withTransaction
import dev.posguard.core.event.GuardEvent
import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.EngineStatusSource
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.IllegalTxnTransitionException
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveredTxn
import dev.posguard.core.ledger.RecoveryOutcome
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TransactionRecord
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnOutcome
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.ledger.TxnStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

/**
 * Room-backed [TransactionLedger].
 *
 * - All writes in this process are serialized by [writeLock]. The version
 *   column (compare-and-set) also protects against writers outside this
 *   instance.
 * - Every state change is validated by [TxnStateMachine] before it is written.
 * - Events go to listeners only after the write is committed.
 */
internal class RoomTransactionLedger(
    private val db: LedgerDatabase,
    private val engineStatus: EngineStatusSource,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    private val listeners: List<GuardEventListener>,
    private val newId: () -> String,
) : TransactionLedger {

    private val dao = db.transactionDao()
    private val writeLock = Mutex()
    private val recoveryLock = Mutex()

    override suspend fun begin(amount: Money, tender: Tender): TxnId = io {
        val now = clock.millis()
        val entity = TransactionEntity(
            id = newId(),
            idempotencyKey = newId(),
            amountCents = amount.amountCents,
            currency = amount.currency,
            tender = tender.name,
            state = TxnState.CREATED.name,
            createdAtMillis = now,
            updatedAtMillis = now,
            engineReference = null,
            resubmitAllowed = false,
            version = 0,
        )
        writeLock.withLock { dao.insert(entity) }
        val id = TxnId(entity.id)
        notify(GuardEvent.TxnStateChanged(id, from = null, to = TxnState.CREATED, at = Instant.ofEpochMilli(now)))
        id
    }

    override suspend fun markSubmitted(id: TxnId): Unit = io {
        mutate(id) { current ->
            if (current.txnState == TxnState.UNKNOWN && !current.resubmitAllowed) {
                // Resubmitting after UNKNOWN is only safe once the engine confirmed
                // it never processed this key (see recoverPending).
                throw IllegalTxnTransitionException(TxnState.UNKNOWN, TxnState.SUBMITTED)
            }
            Change(TxnState.SUBMITTED)
        }
    }

    override suspend fun markResult(id: TxnId, result: EngineResult): Unit = io {
        mutate(id) { current ->
            if (isDuplicate(current, result)) return@mutate null
            when (result) {
                is EngineResult.Authorized -> Change(
                    TxnState.AUTHORIZED,
                    result.engineReference,
                    outcome = TxnOutcome.APPROVED,
                    approvedAmountCents = checkedApproved(current, result.approvedAmount),
                )
                is EngineResult.Declined -> Change(TxnState.DECLINED, result.engineReference, outcome = TxnOutcome.DECLINED)
                EngineResult.Unknown -> Change(TxnState.UNKNOWN)
            }
        }
    }

    override suspend fun markCompleted(id: TxnId): Unit = io {
        mutate(id) { Change(TxnState.COMPLETED) }
    }

    override suspend fun markVoided(id: TxnId): Unit = io {
        mutate(id) { current ->
            if (current.txnState == TxnState.UNKNOWN && !current.resubmitAllowed) {
                // The customer may have been charged. Voiding is only safe once the
                // engine confirmed nothing was processed.
                throw IllegalTxnTransitionException(TxnState.UNKNOWN, TxnState.VOIDED)
            }
            Change(TxnState.VOIDED)
        }
    }

    override suspend fun recoverPending(): List<RecoveredTxn> = io {
        recoveryLock.withLock {
            val pendingStates = TxnState.entries.filter { it.needsRecovery }.map { it.name }
            dao.findByStates(pendingStates).map { snapshot ->
                // Query outside the write lock: the engine may be slow.
                val status = queryStatusSafely(IdempotencyKey(snapshot.idempotencyKey))
                writeLock.withLock { applyRecovery(snapshot.id, status) }
            }
        }
    }

    override suspend fun get(id: TxnId): TransactionRecord? = io { dao.get(id.value)?.toRecord() }

    override fun observe(id: TxnId): Flow<TxnState> =
        dao.observeState(id.value)
            .mapNotNull { it?.let(TxnState::valueOf) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    override fun observeRecent(limit: Int): Flow<List<TransactionRecord>> {
        require(limit > 0) { "limit must be positive." }
        return dao.observeRecent(limit)
            .map { rows -> rows.map { it.toRecord() } }
            .distinctUntilChanged()
            .flowOn(dispatcher)
    }

    // --- internals -------------------------------------------------------------------------

    private data class Change(
        val to: TxnState,
        val engineReference: String? = null,
        val resubmitAllowed: Boolean = false,
        /** Only recovery may rewrite a row without changing its state (to set the resubmit flag). */
        val flagOnly: Boolean = false,
        /** Set with AUTHORIZED / DECLINED. Otherwise the stored outcome is kept. */
        val outcome: TxnOutcome? = null,
        /** Set with AUTHORIZED for a partial approval. Otherwise the stored value is kept. */
        val approvedAmountCents: Long? = null,
    )

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatcher) { block() }

    /**
     * Reads, validates, and writes one transition under [writeLock].
     * [decide] returns null for an idempotent no-op.
     */
    private suspend fun mutate(id: TxnId, decide: (TransactionEntity) -> Change?) {
        val event = writeLock.withLock {
            val current = load(id)
            val change = decide(current) ?: return@withLock null
            write(current, change)
        }
        event?.let(::notify)
    }

    private suspend fun load(id: TxnId): TransactionEntity =
        dao.get(id.value) ?: throw IllegalArgumentException("Unknown transaction $id.")

    /** Validates and writes a change. Must be called with [writeLock] held. Returns the event to emit. */
    private suspend fun write(current: TransactionEntity, change: Change): GuardEvent.TxnStateChanged? {
        val from = current.txnState
        if (!(change.flagOnly && from == change.to)) TxnStateMachine.requireTransition(from, change.to)
        val now = clock.millis()
        val rows = dao.compareAndSet(
            id = current.id,
            expectedVersion = current.version,
            state = change.to.name,
            updatedAtMillis = now,
            engineReference = change.engineReference ?: current.engineReference,
            resubmitAllowed = change.resubmitAllowed,
            outcome = change.outcome?.name ?: current.outcome,
            approvedAmountCents = change.approvedAmountCents ?: current.approvedAmountCents,
        )
        check(rows == 1) { "Transaction ${current.id} was changed concurrently; retry." }
        return if (from != change.to) {
            GuardEvent.TxnStateChanged(TxnId(current.id), from, change.to, Instant.ofEpochMilli(now))
        } else {
            null
        }
    }

    /** True when [result] repeats what is already recorded (a duplicate engine response). */
    private fun isDuplicate(current: TransactionEntity, result: EngineResult): Boolean = when (result) {
        is EngineResult.Authorized ->
            current.txnState == TxnState.AUTHORIZED && current.engineReference == result.engineReference
        is EngineResult.Declined ->
            current.txnState == TxnState.DECLINED &&
                (result.engineReference == null || current.engineReference == result.engineReference)
        EngineResult.Unknown -> current.txnState == TxnState.UNKNOWN
    }

    /** Validates an app-reported partial approval. Throws on an impossible amount. */
    private fun checkedApproved(current: TransactionEntity, approved: Money?): Long? {
        if (approved == null) return null
        require(approved.currency == current.currency) {
            "Approved amount currency ${approved.currency} differs from ${current.currency}."
        }
        require(approved.amountCents <= current.amountCents) {
            "Approved amount ${approved.amountCents} exceeds requested ${current.amountCents}."
        }
        return approved.amountCents.takeIf { it < current.amountCents }
    }

    /** Like [checkedApproved], but recovery must not fail on a bad engine answer: it keeps the full amount. */
    private fun approvedOrNull(current: TransactionEntity, approved: Money?): Long? =
        runCatching { checkedApproved(current, approved) }.getOrNull()

    private suspend fun queryStatusSafely(key: IdempotencyKey): EngineStatus = try {
        engineStatus.queryStatus(key)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        EngineStatus.Unreachable
    }

    /**
     * Applies an engine status to the *current* row (it may have changed while
     * the engine was being queried). Must be called with [writeLock] held.
     */
    private suspend fun applyRecovery(id: String, status: EngineStatus): RecoveredTxn {
        val events = mutableListOf<GuardEvent.TxnStateChanged>()
        val result = db.withTransaction {
            val start = dao.get(id) ?: error("Transaction $id disappeared during recovery.")
            val previous = start.txnState
            if (!previous.needsRecovery) {
                // Resolved by the app while the engine was being queried.
                return@withTransaction RecoveredTxn(previous, start.toRecord(), RecoveryOutcome.RESOLVED)
            }

            var current = start
            suspend fun step(change: Change) {
                write(current, change)?.let(events::add)
                current = dao.get(id)!!
            }

            val outcome = when (status) {
                is EngineStatus.Authorized, is EngineStatus.Declined -> {
                    // CREATED cannot jump to a result: record that the engine did receive it.
                    if (current.txnState == TxnState.CREATED) step(Change(TxnState.SUBMITTED))
                    if (status is EngineStatus.Authorized) {
                        step(
                            Change(
                                TxnState.AUTHORIZED,
                                status.engineReference,
                                outcome = TxnOutcome.APPROVED,
                                approvedAmountCents = approvedOrNull(current, status.approvedAmount),
                            ),
                        )
                    } else {
                        val declined = status as EngineStatus.Declined
                        step(Change(TxnState.DECLINED, declined.engineReference, outcome = TxnOutcome.DECLINED))
                    }
                    RecoveryOutcome.RESOLVED
                }
                EngineStatus.NotProcessed -> {
                    when (current.txnState) {
                        TxnState.CREATED -> Unit // Never sent. It can be submitted normally.
                        TxnState.SUBMITTED -> step(Change(TxnState.UNKNOWN, resubmitAllowed = true))
                        else -> step(Change(TxnState.UNKNOWN, resubmitAllowed = true, flagOnly = true))
                    }
                    RecoveryOutcome.SAFE_TO_RESUBMIT
                }
                EngineStatus.Unreachable -> {
                    // A SUBMITTED payment from before a crash or outage has an unknown outcome.
                    if (current.txnState == TxnState.SUBMITTED) step(Change(TxnState.UNKNOWN))
                    RecoveryOutcome.UNRESOLVED
                }
            }
            RecoveredTxn(previous, current.toRecord(), outcome)
        }
        events.forEach(::notify)
        return result
    }

    private fun notify(event: GuardEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (_: Exception) {
                // A listener must never break the ledger.
            }
        }
    }
}

internal val TransactionEntity.txnState: TxnState get() = TxnState.valueOf(state)

internal fun TransactionEntity.toRecord(): TransactionRecord = TransactionRecord(
    id = TxnId(id),
    idempotencyKey = IdempotencyKey(idempotencyKey),
    amount = Money(amountCents, currency),
    tender = Tender.valueOf(tender),
    state = txnState,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtMillis),
    engineReference = engineReference,
    outcome = outcome?.let(TxnOutcome::valueOf),
    approvedAmount = approvedAmountCents?.let { Money(it, currency) },
)
