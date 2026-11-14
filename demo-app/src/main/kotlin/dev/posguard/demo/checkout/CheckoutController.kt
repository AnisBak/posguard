package dev.posguard.demo.checkout

import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveryOutcome
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TransactionRecord
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.demo.DemoThreats
import dev.posguard.integrity.play.FakeIntegrityVerdictSource
import dev.posguard.integrity.play.IntegrityVerdict
import dev.posguard.mockengine.ChargeRequest
import dev.posguard.mockengine.MockEngineCrashException
import dev.posguard.mockengine.MockPaymentEngine
import dev.posguard.mockengine.MockScenario
import dev.posguard.telemetry.EventType
import dev.posguard.telemetry.InMemorySink
import dev.posguard.telemetry.PosGuardTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Checkout logic for the Sunrise Market demo, following the integration flow
 * in docs/ledger.md:
 *
 * recover → preflight → begin → markSubmitted → charge(same key) → markResult → complete
 *
 * Kept free of Android UI so every demo scenario can be unit-tested.
 */
class CheckoutController(
    private val scope: CoroutineScope,
    private val guard: PosGuard,
    private val ledger: TransactionLedger,
    private val engine: MockPaymentEngine,
    private val threats: DemoThreats,
    private val integrityServer: FakeIntegrityVerdictSource,
    private val telemetry: PosGuardTelemetry,
    private val events: InMemorySink,
    private val resetStore: ResetStore,
    private val scheduleRecovery: () -> Unit,
    private val processKiller: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val state = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = state.asStateFlow()

    private val resetAt = MutableStateFlow(resetStore.resetAtMillis)

    @Volatile private var crashNextPayment = false

    /** Transactions since the last "Reset demo", newest first. */
    val history: Flow<List<TransactionRecord>> =
        combine(ledger.observeRecent(HISTORY_LIMIT), resetAt) { records, since ->
            records.filter { it.createdAt.toEpochMilli() >= since }
        }

    val telemetryEvents = events.events

    /** Call once at app start: recovers pending payments before new ones are allowed. */
    suspend fun start() {
        scope.launch {
            // Duplicate / late engine responses go to the ledger, which ignores repeats.
            engine.lateResponses.collect { late ->
                val record = ledger.recentByKey(late.idempotencyKey.value) ?: return@collect
                try {
                    ledger.markResult(record.id, late.result)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    telemetry.record(EventType.APP_EVENT, "late engine response ignored")
                }
            }
        }
        recover(startup = true)
    }

    // --- Cart ---------------------------------------------------------------------------

    fun add(productId: String) = state.update { it.copy(cart = it.cart + (productId to (it.cart[productId] ?: 0) + 1)) }

    fun remove(productId: String) = state.update {
        val qty = (it.cart[productId] ?: 0) - 1
        it.copy(cart = if (qty <= 0) it.cart - productId else it.cart + (productId to qty))
    }

    fun dismissBanner() = state.update { it.copy(banner = null) }

    fun backToShop() = state.update { it.copy(screen = Screen.Shop, busy = false) }

    // --- Checkout -----------------------------------------------------------------------

    /** "Take payment": preflight decides whether the payment screen may open. */
    suspend fun takePayment() {
        val current = state.value
        if (!current.canTakePayment) return
        state.update { it.copy(busy = true) }
        val verdict = guard.preflight()
        val amount = Money(current.totalCents, CURRENCY)
        state.update {
            it.copy(
                busy = false,
                screen = when (verdict) {
                    is GuardVerdict.Block, is GuardVerdict.Warn -> Screen.Flagged(verdict)
                    is GuardVerdict.Allow -> Screen.Payment(amount, PaymentPhase.ReadyToTap, verdict)
                },
            )
        }
    }

    /** After a Warn notice, the merchant may proceed. */
    fun continueAfterWarning() = state.update {
        val flagged = it.screen as? Screen.Flagged ?: return@update it
        if (!flagged.canContinue) return@update it
        it.copy(screen = Screen.Payment(Money(it.totalCents, CURRENCY), PaymentPhase.ReadyToTap, flagged.verdict))
    }

    /** "Tap card (simulated)": runs the journaled charge. */
    suspend fun tapCard() {
        val payment = state.value.screen as? Screen.Payment ?: return
        if (payment.phase != PaymentPhase.ReadyToTap) return
        setPhase(PaymentPhase.Processing)

        val id = ledger.begin(payment.amount, Tender.CARD)
        submitAndRecord(id, crashAllowed = true)
    }

    /** After the engine confirmed nothing was processed: resubmit with the SAME key. */
    suspend fun resubmit() {
        val phase = (state.value.screen as? Screen.Payment)?.phase as? PaymentPhase.SafeToResubmit ?: return
        setPhase(PaymentPhase.Processing)
        submitAndRecord(phase.txnId, crashAllowed = false)
    }

    /** Restores the (simulated) network and resolves pending payments by idempotency key. */
    suspend fun reconnect() {
        engine.reconnect()
        recover(startup = false)
        val payment = state.value.screen as? Screen.Payment ?: return
        val txnId = (payment.phase as? PaymentPhase.Unknown)?.txnId ?: return
        val record = ledger.get(txnId) ?: return
        setPhase(phaseFor(record, txnId))
    }

    private suspend fun submitAndRecord(id: TxnId, crashAllowed: Boolean) {
        val record = checkNotNull(ledger.get(id))
        ledger.markSubmitted(id)
        val result = try {
            engine.charge(ChargeRequest(record.amount, record.tender, record.idempotencyKey))
        } catch (_: MockEngineCrashException) {
            EngineResult.Unknown
        }
        if (crashAllowed && crashNextPayment) {
            // Scenario 5: the app dies after the engine charged, before the result is recorded.
            crashNextPayment = false
            processKiller()
            return
        }
        ledger.markResult(id, result)
        when (result) {
            is EngineResult.Authorized -> {
                ledger.markCompleted(id)
                setPhase(phaseFor(checkNotNull(ledger.get(id)), id))
            }
            // Declined payments rest in DECLINED so history keeps the outcome
            // (a COMPLETED record does not say whether it was approved).
            is EngineResult.Declined -> setPhase(PaymentPhase.Declined(result.declineCode))
            EngineResult.Unknown -> {
                scheduleRecovery()
                setPhase(PaymentPhase.Unknown(id))
                // CLAUDE.md §7: pending payments are resolved before new ones start.
                state.update { it.copy(startup = StartupState.Pending(1)) }
            }
        }
        clearOneShotArmed()
    }

    private suspend fun phaseFor(record: TransactionRecord, id: TxnId): PaymentPhase = when (record.state) {
        TxnState.AUTHORIZED, TxnState.COMPLETED -> {
            if (record.state == TxnState.AUTHORIZED) ledger.markCompleted(id)
            PaymentPhase.Approved(record.engineReference, chargesFor(record))
        }
        TxnState.DECLINED -> PaymentPhase.Declined(declineCodeFor(record))
        TxnState.UNKNOWN, TxnState.CREATED -> {
            // Still pending: SAFE_TO_RESUBMIT if the engine confirmed nothing was processed.
            val safe = engine.queryStatusIsNotProcessed(record)
            if (safe) PaymentPhase.SafeToResubmit(id) else PaymentPhase.Unknown(id)
        }
        else -> PaymentPhase.Unknown(id)
    }

    /**
     * Resolves every pending payment. At startup, payments the engine confirmed it
     * never processed are voided (the customer was not charged).
     */
    private suspend fun recover(startup: Boolean) {
        val recovered = ledger.recoverPending()
        val messages = mutableListOf<String>()
        var unresolved = 0
        val current = (state.value.screen as? Screen.Payment)?.phase
        val activeTxn = (current as? PaymentPhase.Unknown)?.txnId ?: (current as? PaymentPhase.SafeToResubmit)?.txnId
        for (r in recovered) {
            when (r.outcome) {
                RecoveryOutcome.RESOLVED -> {
                    // Only approved payments are completed. Declined ones rest in DECLINED (see above).
                    if (r.record.state == TxnState.AUTHORIZED) ledger.markCompleted(r.record.id)
                    val what = if (r.record.state == TxnState.DECLINED) "declined" else "approved, charged ${chargesFor(r.record)}×"
                    messages += "Recovered ${formatCents(r.record.amount.amountCents)} payment: $what"
                }
                RecoveryOutcome.SAFE_TO_RESUBMIT -> if (startup || r.record.id != activeTxn) {
                    ledger.markVoided(r.record.id)
                    messages += "Voided ${formatCents(r.record.amount.amountCents)} payment: never charged"
                }
                RecoveryOutcome.UNRESOLVED -> unresolved++
            }
        }
        if (unresolved > 0) scheduleRecovery()
        state.update {
            it.copy(
                startup = if (unresolved > 0) StartupState.Pending(unresolved) else StartupState.Ready,
                banner = messages.joinToString("\n").ifEmpty { it.banner },
            )
        }
    }

    // --- Demo controls ------------------------------------------------------------------

    fun arm(scenario: DemoScenario) {
        when (scenario) {
            DemoScenario.NORMAL -> {
                disarmAll()
                return
            }
            DemoScenario.OVERLAY -> threats.setOverlay(true)
            DemoScenario.RECORDING -> threats.setRecording(true)
            DemoScenario.NETWORK_DROP -> engine.setNextScenario(MockScenario.NETWORK_DROP_AFTER_SUBMIT)
            DemoScenario.APP_CRASH -> {
                crashNextPayment = true
                engine.setNextScenario(MockScenario.APPROVE)
            }
            DemoScenario.DECLINE -> engine.setNextScenario(MockScenario.DECLINE)
            DemoScenario.ENGINE_CRASH -> engine.setNextScenario(MockScenario.ENGINE_CRASH)
            DemoScenario.TIMEOUT -> engine.setNextScenario(MockScenario.TIMEOUT)
            DemoScenario.DUPLICATE_RESPONSE -> engine.setNextScenario(MockScenario.DUPLICATE_RESPONSE)
            DemoScenario.DEVICE_INTEGRITY_FAIL ->
                integrityServer.verdict = IntegrityVerdict.HEALTHY.copy(deviceRecognition = emptySet())
        }
        state.update { it.copy(armed = it.armed + scenario) }
    }

    /** "Reset demo": clears simulated threats, scenarios, processor memory, and the event panel. */
    suspend fun resetDemo() {
        disarmAll()
        engine.reset()
        events.clear()
        val now = clock.millis()
        resetStore.resetAtMillis = now
        resetAt.value = now
        state.value = UiState(startup = state.value.startup)
        recover(startup = true)
    }

    /** Shows the scrubber live: this message contains a documented TEST card number. */
    fun logScrubbedTestError() {
        telemetry.record(
            EventType.APP_ERROR,
            message = "Decline for card 4111 1111 1111 1111, receipt to jane.doe@example.com",
            attributes = mapOf("lane" to "1", "cardNumber" to "4111111111111111"),
        )
    }

    private fun disarmAll() {
        threats.clear()
        crashNextPayment = false
        engine.setNextScenario(engine.defaultScenario)
        integrityServer.verdict = IntegrityVerdict.HEALTHY
        state.update { it.copy(armed = emptySet()) }
    }

    /** Engine scenarios are one-shot. Threat toggles stay armed until Normal or Reset. */
    private fun clearOneShotArmed() = state.update {
        it.copy(armed = it.armed - ONE_SHOT)
    }

    private fun setPhase(phase: PaymentPhase) = state.update {
        val payment = it.screen as? Screen.Payment ?: return@update it
        it.copy(screen = payment.copy(phase = phase))
    }

    private suspend fun chargesFor(record: TransactionRecord): Int =
        engine.processedCharges().count { it.idempotencyKey == record.idempotencyKey.value }

    private suspend fun declineCodeFor(record: TransactionRecord): String? =
        engine.processedCharges().firstOrNull { it.idempotencyKey == record.idempotencyKey.value }?.declineCode

    private suspend fun MockPaymentEngine.queryStatusIsNotProcessed(record: TransactionRecord): Boolean =
        queryStatus(record.idempotencyKey) == EngineStatus.NotProcessed

    /** observeRecent's first emission is the current list. */
    private suspend fun TransactionLedger.recentByKey(key: String): TransactionRecord? =
        observeRecent(HISTORY_LIMIT).first().firstOrNull { it.idempotencyKey.value == key }

    companion object {
        const val CURRENCY: String = "USD"
        const val HISTORY_LIMIT: Int = 50
        private val ONE_SHOT = setOf(
            DemoScenario.NETWORK_DROP, DemoScenario.APP_CRASH, DemoScenario.DECLINE,
            DemoScenario.ENGINE_CRASH, DemoScenario.TIMEOUT, DemoScenario.DUPLICATE_RESPONSE,
        )
    }
}
