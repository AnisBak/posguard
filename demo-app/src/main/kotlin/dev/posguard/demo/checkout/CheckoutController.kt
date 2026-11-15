package dev.posguard.demo.checkout

import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.EngineStatusSource
import dev.posguard.core.ledger.combinedStatusSource
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveryOutcome
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TransactionRecord
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnOutcome
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.demo.DemoThreats
import dev.posguard.ebt.EbtCheckout
import dev.posguard.ebt.EbtEntryMode
import dev.posguard.ebt.EbtLegResult
import dev.posguard.ebt.EbtReceipt
import dev.posguard.ebt.EbtReceiptLeg
import dev.posguard.ebt.EbtTenderPlanner
import dev.posguard.ebt.EntryPrompt
import dev.posguard.ebt.MerchantInfo
import dev.posguard.ebtsim.EbtSimScenario
import dev.posguard.ebtsim.EbtSimulator
import dev.posguard.ebtsim.SimEbtCard
import dev.posguard.ebtsim.SimCardType
import dev.posguard.integrity.relay.EntryMode
import dev.posguard.integrity.relay.FlaggedTap
import dev.posguard.integrity.relay.RelayRiskAssessment
import dev.posguard.integrity.relay.RelayRiskMonitor
import dev.posguard.integrity.relay.TapMetadata
import dev.posguard.integrity.play.FakeIntegrityVerdictSource
import dev.posguard.integrity.play.IntegrityVerdict
import dev.posguard.mockengine.ChargeRequest
import dev.posguard.mockengine.MockEngineCrashException
import dev.posguard.mockengine.MockEntryMode
import dev.posguard.mockengine.MockPaymentEngine
import dev.posguard.mockengine.MockScenario
import dev.posguard.mockengine.MockTapInfo
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
import java.time.ZoneId

/**
 * Checkout logic for the Sunrise Market demo, following the integration flow
 * in docs/ledger.md:
 *
 * recover → preflight → begin → markSubmitted → charge(same key) → markResult
 *   → relay-risk check (hold for review if flagged) → complete
 *
 * EBT (scenario 9): preflight → split basket → SNAP leg via EbtCheckout
 *   (chip-first entry, fallback) → rest by card → receipt. Simulator only.
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
    private val relay: RelayRiskMonitor,
    private val ebt: EbtCheckout,
    private val ebtSim: EbtSimulator,
    private val resetStore: ResetStore,
    private val scheduleRecovery: () -> Unit,
    private val processKiller: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val state = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = state.asStateFlow()

    private val resetAt = MutableStateFlow(resetStore.resetAtMillis)

    @Volatile private var crashNextPayment = false

    /** Asks both engines, so a pending EBT leg is never judged by the card engine alone. */
    private val statusSource: EngineStatusSource = combinedStatusSource(engine, ebtSim)

    /** Transactions since the last "Reset demo", newest first. */
    val history: Flow<List<TransactionRecord>> =
        combine(ledger.observeRecent(HISTORY_LIMIT), resetAt) { records, since ->
            records.filter { it.createdAt.toEpochMilli() >= since }
        }

    val telemetryEvents = events.events

    /** Approved payments flagged by relay-risk rules, waiting for the merchant's review. */
    val heldForReview: StateFlow<List<FlaggedTap>> = relay.pendingReview

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

    /** After an approved sale: empty the cart and return to the shop. */
    fun finishSale() = state.update { it.copy(cart = emptyMap(), screen = Screen.Shop, busy = false) }

    /** Shows a message to the merchant, for example an unexpected error. */
    fun showBanner(text: String) = state.update { it.copy(banner = text, busy = false) }

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
        ebtSim.reconnect()
        recover(startup = false)
        val payment = state.value.screen as? Screen.Payment ?: return
        val txnId = (payment.phase as? PaymentPhase.Unknown)?.txnId ?: return
        val record = ledger.get(txnId) ?: return
        setPhase(phaseFor(record, txnId))
    }

    /** "Reviewed, complete sale" on a payment held for review. */
    suspend fun completeReviewed() {
        val held = (state.value.screen as? Screen.Payment)?.phase as? PaymentPhase.HeldForReview ?: return
        markReviewed(held.txnId)
        setPhase(PaymentPhase.Approved(held.engineReference, held.processorCharges))
    }

    /** Reviews a held payment from the Demo-controls list (for example after "Review later"). */
    suspend fun markReviewed(txnId: TxnId) {
        relay.markReviewed(txnId)
        if (ledger.get(txnId)?.state == TxnState.AUTHORIZED) ledger.markCompleted(txnId)
    }

    // --- EBT (scenario 9) -------------------------------------------------------------------

    /** "Pay with EBT": shows the SNAP split before any card is read. */
    fun startEbt() = state.update {
        val payment = it.screen as? Screen.Payment ?: return@update it
        if (payment.phase != PaymentPhase.ReadyToTap) return@update it
        val split = ebt.split(Catalog.basket(it.cart))
        it.copy(screen = payment.copy(phase = PaymentPhase.EbtSplit(split, EbtTenderPlanner.plan(split))))
    }

    /** Charges the SNAP leg: chip first, with fallback, through the ledger. */
    suspend fun payEbt() {
        val phase = (state.value.screen as? Screen.Payment)?.phase as? PaymentPhase.EbtSplit ?: return
        val snapLeg = phase.legs.firstOrNull { it.tender == Tender.EBT_SNAP } ?: return
        val log = mutableListOf<String>()
        setPhase(PaymentPhase.EbtProcessing(log.toList()))

        val result = ebt.payLeg(snapLeg) { prompt ->
            log += promptText(prompt)
            setPhase(PaymentPhase.EbtProcessing(log.toList()))
        }
        when (result) {
            is EbtLegResult.Approved -> {
                log += "Customer entered PIN on the engine's secure PIN entry (simulated)"
                log += if (result.isPartial) {
                    "SNAP approved ${formatCents(result.approved.amountCents)} of " +
                        "${formatCents(result.requested.amountCents)} (partial approval)"
                } else {
                    "SNAP approved ${formatCents(result.approved.amountCents)}"
                }
                val remaining = EbtTenderPlanner.afterSnapApproval(snapLeg, result.approved, phase.legs.drop(1))
                if (remaining.isEmpty()) {
                    ledger.markCompleted(result.txnId)
                    showReceipt(listOf(EbtReceipt.leg(result)), result, log)
                } else {
                    setPhase(PaymentPhase.EbtRemainder(result, remaining.single(), log.toList()))
                }
            }
            is EbtLegResult.Declined -> {
                ledger.markCompleted(result.txnId)
                setPhase(
                    PaymentPhase.EbtFailed(
                        "❌ SNAP declined",
                        "Decline code ${result.declineCode ?: "-"} (simulated). Nothing was taken from SNAP. Ask for another tender.",
                        log.toList(),
                    ),
                )
                endEbtScenario()
            }
            is EbtLegResult.CardUnreadable -> {
                log += "No allowed entry mode could read the card"
                setPhase(
                    PaymentPhase.EbtFailed(
                        "⚠ Card could not be read",
                        "Tried ${result.attempts.joinToString(" → ") { modeLabel(it.mode) }}. posguard never falls back to " +
                            "typing the card number. Nothing was charged. Ask for another tender.",
                        log.toList(),
                    ),
                )
                endEbtScenario()
            }
            is EbtLegResult.Unknown -> pending(result.txnId)
        }
    }

    /** Charges the non-SNAP rest (and any partial-approval shortfall) by card. */
    suspend fun payRemainderByCard() {
        val phase = (state.value.screen as? Screen.Payment)?.phase as? PaymentPhase.EbtRemainder ?: return
        setPhase(PaymentPhase.Processing)
        val id = ledger.begin(phase.remaining.amount, Tender.CARD)
        val record = checkNotNull(ledger.get(id))
        ledger.markSubmitted(id)
        val result = try {
            engine.charge(ChargeRequest(record.amount, record.tender, record.idempotencyKey))
        } catch (_: MockEngineCrashException) {
            EngineResult.Unknown
        }
        ledger.markResult(id, result)
        // The SNAP part is finished whatever happens to the card part.
        if (ledger.get(phase.snap.txnId)?.state == TxnState.AUTHORIZED) ledger.markCompleted(phase.snap.txnId)
        val log = phase.log + "Card for the rest: ${formatCents(phase.remaining.amount.amountCents)}"
        when (result) {
            is EngineResult.Authorized -> {
                ledger.markCompleted(id)
                val cardLeg = EbtReceiptLeg(
                    Tender.CARD, phase.remaining.amount, phase.remaining.amount,
                    codedTransactionNumber = result.engineReference,
                )
                showReceipt(listOf(EbtReceipt.leg(phase.snap), cardLeg), phase.snap, log)
            }
            is EngineResult.Declined -> {
                ledger.markCompleted(id)
                setPhase(
                    PaymentPhase.EbtFailed(
                        "❌ Card declined for the rest",
                        "SNAP paid ${formatCents(phase.snap.approved.amountCents)}. " +
                            "Collect ${formatCents(phase.remaining.amount.amountCents)} another way.",
                        log,
                    ),
                )
                endEbtScenario()
            }
            EngineResult.Unknown -> pending(id)
        }
    }

    private suspend fun showReceipt(legs: List<EbtReceiptLeg>, snap: EbtLegResult.Approved, log: List<String>) {
        val receipt = EbtReceipt(MERCHANT, clock.instant(), legs, snap.balance)
        // On the POS screen balances are masked: they belong on the customer receipt only.
        setPhase(PaymentPhase.EbtDone(receipt.lines(ZoneId.systemDefault(), includeBalance = false), log))
        endEbtScenario()
    }

    /** A leg with an unknown result: same path as a card payment (scenario 4). */
    private fun pending(id: TxnId) {
        scheduleRecovery()
        setPhase(PaymentPhase.Unknown(id))
        state.update { it.copy(startup = StartupState.Pending(1)) }
        clearOneShotArmed()
    }

    /** EBT scenarios last for one EBT sale. The next customer gets the default fictional card. */
    private suspend fun endEbtScenario() {
        if (state.value.armed.none { it in EBT_SCENARIOS }) return
        ebtSim.presentCard(DEFAULT_EBT_CARD)
        ebtSim.setNextScenario(EbtSimScenario.NORMAL)
        state.update { it.copy(armed = it.armed - EBT_SCENARIOS) }
    }

    private fun promptText(prompt: EntryPrompt.Present): String {
        val action = when (prompt.mode) {
            EbtEntryMode.CHIP_INSERT -> "Insert card (chip)"
            EbtEntryMode.CHIP_TAP -> "Tap card (contactless)"
            EbtEntryMode.MAGSTRIPE -> "Swipe card (magstripe fallback)"
        }
        val from = prompt.fallbackFrom ?: return action
        val why = from.failure.name.lowercase().replace('_', ' ')
        return "${modeLabel(from.mode)} failed ($why) → $action"
    }

    private fun modeLabel(mode: EbtEntryMode): String = when (mode) {
        EbtEntryMode.CHIP_INSERT -> "Chip insert"
        EbtEntryMode.CHIP_TAP -> "Tap"
        EbtEntryMode.MAGSTRIPE -> "Swipe"
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
                val assessment = relay.record(tapMetadata(record))
                if (assessment is RelayRiskAssessment.Flagged) {
                    // Hold: stays AUTHORIZED until reviewed. posguard never declines on relay risk.
                    setPhase(
                        PaymentPhase.HeldForReview(
                            id, assessment.hits.map { it.explanation }, result.engineReference, chargesFor(record),
                        ),
                    )
                } else {
                    ledger.markCompleted(id)
                    setPhase(phaseFor(checkNotNull(ledger.get(id)), id))
                }
            }
            is EngineResult.Declined -> {
                // The record keeps its outcome (APPROVED / DECLINED) after completion.
                ledger.markCompleted(id)
                setPhase(phaseFor(checkNotNull(ledger.get(id)), id))
            }
            EngineResult.Unknown -> {
                scheduleRecovery()
                setPhase(PaymentPhase.Unknown(id))
                // CLAUDE.md §7: pending payments are resolved before new ones start.
                state.update { it.copy(startup = StartupState.Pending(1)) }
            }
        }
        clearOneShotArmed()
    }

    /**
     * The "engine adapter": maps what the payment engine reports about the tap
     * (here the mock's MockTapInfo) to posguard's non-sensitive TapMetadata.
     */
    private suspend fun tapMetadata(record: TransactionRecord): TapMetadata {
        val info = engine.tapInfo(record.idempotencyKey)
        return TapMetadata(
            txnId = record.id,
            at = clock.instant(),
            amount = record.amount,
            entryMode = when (info?.entryMode) {
                MockEntryMode.CONTACTLESS_CARD -> EntryMode.CONTACTLESS_CARD
                MockEntryMode.CONTACTLESS_WALLET -> EntryMode.CONTACTLESS_WALLET
                MockEntryMode.CHIP -> EntryMode.OTHER
                null -> EntryMode.UNKNOWN
            },
            cardReadMillis = info?.cardReadMillis,
        )
    }

    private suspend fun phaseFor(record: TransactionRecord, id: TxnId): PaymentPhase = when (record.state) {
        TxnState.AUTHORIZED, TxnState.DECLINED, TxnState.COMPLETED -> {
            if (record.state != TxnState.COMPLETED) ledger.markCompleted(id)
            if (record.outcome == TxnOutcome.DECLINED) {
                PaymentPhase.Declined(declineCodeFor(record))
            } else {
                PaymentPhase.Approved(record.engineReference, chargesFor(record))
            }
        }
        TxnState.UNKNOWN, TxnState.CREATED -> {
            // Still pending: SAFE_TO_RESUBMIT if the engine confirmed nothing was processed.
            val safe = statusSource.queryStatus(record.idempotencyKey) == EngineStatus.NotProcessed
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
                    if (r.record.state == TxnState.AUTHORIZED || r.record.state == TxnState.DECLINED) {
                        ledger.markCompleted(r.record.id)
                    }
                    val what = if (r.record.outcome == TxnOutcome.DECLINED) "declined" else "approved, charged ${chargesFor(r.record)}×"
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

    suspend fun arm(scenario: DemoScenario) {
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
            DemoScenario.RELAY_BURST -> {
                prerunWalletTaps()
                engine.setNextScenario(MockScenario.APPROVE)
                engine.setNextTapProfile(WALLET_TAP)
                showBanner("Scenario 7: $PRERUN_TAPS phone-wallet taps were just processed. Take one more payment.")
            }
            DemoScenario.DEVICE_INTEGRITY_FAIL ->
                integrityServer.verdict = IntegrityVerdict.HEALTHY.copy(deviceRecognition = emptySet())
            DemoScenario.EBT_PURCHASE -> {
                ebtSim.presentCard(SCENARIO_9_CARD)
                ebtSim.setNextScenario(EbtSimScenario.CHIP_FAILS_ONCE)
                state.update { it.copy(cart = SCENARIO_9_BASKET, screen = Screen.Shop) }
                showBanner("Scenario 9: basket loaded (food, soda, soap, hot coffee). Take payment → Pay with EBT.")
            }
            DemoScenario.EBT_MAGSTRIPE_CARD -> ebtSim.presentCard(EbtSimulator.Cards.MAGSTRIPE_ONLY)
            DemoScenario.EBT_CHIP_APP_UNKNOWN -> ebtSim.setNextScenario(EbtSimScenario.CHIP_APP_NOT_RECOGNIZED)
            DemoScenario.EBT_NO_BALANCE -> ebtSim.presentCard(EbtSimulator.Cards.EMPTY_SNAP)
        }
        state.update { it.copy(armed = it.armed + scenario) }
    }

    /**
     * Scenario 7 setup: real small wallet payments through the full path (ledger,
     * mock engine, relay-risk monitor), so the presenter's next tap completes a burst.
     */
    private suspend fun prerunWalletTaps() {
        repeat(PRERUN_TAPS) {
            val id = ledger.begin(Money(PRERUN_CENTS, CURRENCY), Tender.CARD)
            val record = checkNotNull(ledger.get(id))
            ledger.markSubmitted(id)
            engine.setNextScenario(MockScenario.APPROVE)
            engine.setNextTapProfile(WALLET_TAP)
            val result = engine.charge(ChargeRequest(record.amount, record.tender, record.idempotencyKey))
            ledger.markResult(id, result)
            if (result is EngineResult.Authorized && relay.record(tapMetadata(record)) is RelayRiskAssessment.Clear) {
                ledger.markCompleted(id)
            }
        }
    }

    /** "Reset demo": clears simulated threats, scenarios, processor memory, and the event panel. */
    suspend fun resetDemo() {
        disarmAll()
        relay.clear()
        engine.reset()
        ebtSim.reset()
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

    private suspend fun disarmAll() {
        threats.clear()
        ebtSim.presentCard(DEFAULT_EBT_CARD)
        ebtSim.setNextScenario(EbtSimScenario.NORMAL)
        crashNextPayment = false
        engine.setNextScenario(engine.defaultScenario)
        engine.setNextTapProfile(engine.defaultTapProfile)
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

    private suspend fun chargesFor(record: TransactionRecord): Int = when (record.tender) {
        Tender.CARD -> engine.processedCharges().count { it.idempotencyKey == record.idempotencyKey.value }
        else -> ebtSim.processed().count { it.idempotencyKey == record.idempotencyKey.value }
    }

    private suspend fun declineCodeFor(record: TransactionRecord): String? =
        engine.processedCharges().firstOrNull { it.idempotencyKey == record.idempotencyKey.value }?.declineCode

    /** observeRecent's first emission is the current list. */
    private suspend fun TransactionLedger.recentByKey(key: String): TransactionRecord? =
        observeRecent(HISTORY_LIMIT).first().firstOrNull { it.idempotencyKey.value == key }

    companion object {
        const val CURRENCY: String = "USD"
        const val HISTORY_LIMIT: Int = 50
        private val ONE_SHOT = setOf(
            DemoScenario.NETWORK_DROP, DemoScenario.APP_CRASH, DemoScenario.DECLINE,
            DemoScenario.ENGINE_CRASH, DemoScenario.TIMEOUT, DemoScenario.DUPLICATE_RESPONSE,
            DemoScenario.RELAY_BURST,
        )
        private const val PRERUN_TAPS = 2
        private const val PRERUN_CENTS = 250L
        private val WALLET_TAP = MockTapInfo(MockEntryMode.CONTACTLESS_WALLET)

        private val EBT_SCENARIOS = setOf(
            DemoScenario.EBT_PURCHASE, DemoScenario.EBT_MAGSTRIPE_CARD,
            DemoScenario.EBT_CHIP_APP_UNKNOWN, DemoScenario.EBT_NO_BALANCE,
        )

        /** Fictional store details printed on receipts. */
        val MERCHANT = MerchantInfo("Sunrise Market", "1 Example Way, Springfield (fictional)")

        val DEFAULT_EBT_CARD: SimEbtCard = EbtSimulator.Cards.CHIP_CONTACTLESS

        /** Scenario 9: SNAP balance below the eligible total, so the approval is partial. */
        val SCENARIO_9_CARD = SimEbtCard("Chip + tap card, $5.00 SNAP (fictional)", SimCardType.CHIP_CONTACTLESS, snapCents = 500)

        /** Eligible: bananas ×2, juice ($7.97). Not eligible: soda (restricted), soap, hot coffee ($8.74). */
        val SCENARIO_9_BASKET = mapOf("bananas" to 2, "juice" to 1, "soda" to 1, "soap" to 1, "coffee" to 1)
    }
}
