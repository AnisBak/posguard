package dev.posguard.core.ledger

/**
 * Transaction lifecycle. See [TxnStateMachine] for allowed transitions and
 * docs/decisions/ADR-002-idempotent-ledger.md for the reasoning.
 */
public enum class TxnState {
    /** Recorded locally with an idempotency key. Not yet confirmed as sent to the engine. */
    CREATED,

    /** Handed to the engine. The outcome is not known yet. */
    SUBMITTED,

    /** The engine approved the payment. */
    AUTHORIZED,

    /** The engine declined the payment. */
    DECLINED,

    /** The outcome is unknown (connection lost after submit, or a crash). Must be resolved by a status query. */
    UNKNOWN,

    /** Finished. No further changes. */
    COMPLETED,

    /** Cancelled or reversed. No further changes. */
    VOIDED,
    ;

    /** True for [COMPLETED] and [VOIDED]. */
    public val isTerminal: Boolean get() = this == COMPLETED || this == VOIDED

    /**
     * True when the engine may have received the payment but the result is not
     * recorded yet. Such transactions must be resolved by
     * [TransactionLedger.recoverPending] before new payments start.
     */
    public val needsRecovery: Boolean get() = this == CREATED || this == SUBMITTED || this == UNKNOWN
}

/** Thrown when a ledger operation would make a transition not allowed by [TxnStateMachine]. */
public class IllegalTxnTransitionException(
    public val from: TxnState,
    public val to: TxnState,
) : IllegalStateException("Transaction cannot move from $from to $to.")

/** The single source of truth for allowed [TxnState] transitions. */
public object TxnStateMachine {
    private val allowed: Map<TxnState, Set<TxnState>> = mapOf(
        TxnState.CREATED to setOf(TxnState.SUBMITTED, TxnState.VOIDED),
        TxnState.SUBMITTED to setOf(TxnState.AUTHORIZED, TxnState.DECLINED, TxnState.UNKNOWN),
        // UNKNOWN -> SUBMITTED is a resubmission with the SAME idempotency key. It is
        // allowed only after the engine confirms nothing was processed
        // (EngineStatus.NotProcessed). The ledger implementation enforces that.
        TxnState.UNKNOWN to setOf(TxnState.AUTHORIZED, TxnState.DECLINED, TxnState.SUBMITTED, TxnState.VOIDED),
        TxnState.AUTHORIZED to setOf(TxnState.COMPLETED, TxnState.VOIDED),
        TxnState.DECLINED to setOf(TxnState.COMPLETED),
        TxnState.COMPLETED to emptySet(),
        TxnState.VOIDED to emptySet(),
    )

    /** States reachable in one step from [from]. */
    public fun nextStates(from: TxnState): Set<TxnState> = allowed.getValue(from)

    public fun canTransition(from: TxnState, to: TxnState): Boolean = to in allowed.getValue(from)

    /** @throws IllegalTxnTransitionException if the transition is not allowed. */
    public fun requireTransition(from: TxnState, to: TxnState) {
        if (!canTransition(from, to)) throw IllegalTxnTransitionException(from, to)
    }
}
