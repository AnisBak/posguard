package dev.posguard.ebtsim

import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.ebt.CardRead
import dev.posguard.ebt.EbtBalance
import dev.posguard.ebt.EbtEngine
import dev.posguard.ebt.EbtEntryMode
import dev.posguard.ebt.EbtPurchaseRequest
import dev.posguard.ebt.EbtPurchaseResult
import dev.posguard.ebt.EntryFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration

/** What kind of fictional EBT card the simulator pretends was presented. */
public enum class SimCardType {
    /** Older card with only a magnetic stripe (most EBT cards before the chip transition). */
    MAGSTRIPE_ONLY,

    /** Chip card that can only be inserted. */
    CHIP,

    /** Chip card that can be inserted or tapped (NFC). */
    CHIP_CONTACTLESS,
}

/**
 * A fictional EBT card. **NOT REAL.** No card number, no PIN: just a type and
 * fictional balances in cents.
 */
public data class SimEbtCard(
    val label: String,
    val type: SimCardType,
    val snapCents: Long,
    val cashCents: Long = 0,
    val currency: String = "USD",
) {
    init {
        require(snapCents >= 0 && cashCents >= 0) { "Balances can't be negative." }
    }

    override fun toString(): String = "SimEbtCard($label, $type)"
}

/** One-shot behaviors for the next card read or purchase. */
public enum class EbtSimScenario {
    /** Everything works. Purchases approve up to the balance (partial approval when it's short). */
    NORMAL,

    /** The next chip read (insert or tap) fails once, then chip reads work. */
    CHIP_FAILS_ONCE,

    /** The reader doesn't recognize the SNAP chip app (POS not updated). Chip reads fail until reset. */
    CHIP_APP_NOT_RECOGNIZED,

    /** The processor approves the next purchase, but the response is lost and the engine goes offline. */
    NETWORK_DROP_AFTER_SUBMIT,
}

/** What the simulated processor recorded for one idempotency key. */
public data class SimProcessed(
    val idempotencyKey: String,
    val tender: Tender,
    val requestedCents: Long,
    val approvedCents: Long,
    val currency: String,
    val engineReference: String,
    val declineCode: String?,
) {
    public val approved: Boolean get() = declineCode == null
}

/**
 * **DEMO — NOT A REAL EBT PROCESSOR.**
 *
 * Simulates a certified EBT-capable engine and its processor for tests and the
 * demo app. It never reads cards, never handles PINs (it assumes the engine's
 * secure PIN entry succeeded), and never touches real benefit accounts.
 *
 * - Card reads follow the presented [SimEbtCard]'s type: a magstripe-only card
 *   reports [EntryFailure.CARD_HAS_NO_CHIP] to chip modes, and a chip card
 *   without NFC reports [EntryFailure.MODE_NOT_SUPPORTED] to taps.
 * - Purchases deduplicate by idempotency key and approve up to the remaining
 *   balance: less than requested is a partial approval, and an empty balance
 *   is declined with [DECLINE_INSUFFICIENT_FUNDS].
 */
public class EbtSimulator private constructor(
    private val latency: Duration,
    private val dispatcher: CoroutineDispatcher,
    card: SimEbtCard,
) : EbtEngine {

    private val lock = Mutex()
    private val processed = LinkedHashMap<String, SimProcessed>()
    private val reads = HashMap<String, EbtEntryMode>()
    private val network = MutableStateFlow(true)

    @Volatile private var scenario: EbtSimScenario = EbtSimScenario.NORMAL
    @Volatile private var appNotRecognized = false

    private var startCard: SimEbtCard = card
    private var snapCents = card.snapCents
    private var cashCents = card.cashCents

    /** The card the simulated customer presents. */
    @Volatile public var card: SimEbtCard = card
        private set

    /** Whether the simulated processor is reachable. */
    public val networkAvailable: StateFlow<Boolean> = network.asStateFlow()

    /** Presents a different fictional card, with its starting balances. */
    public suspend fun presentCard(card: SimEbtCard): Unit = lock.withLock {
        this.card = card
        startCard = card
        snapCents = card.snapCents
        cashCents = card.cashCents
    }

    /** Arms a one-shot [EbtSimScenario]. */
    public fun setNextScenario(scenario: EbtSimScenario) {
        this.scenario = scenario
        appNotRecognized = scenario == EbtSimScenario.CHIP_APP_NOT_RECOGNIZED
    }

    public fun disconnect() {
        network.value = false
    }

    public fun reconnect() {
        network.value = true
    }

    override suspend fun readCard(mode: EbtEntryMode): CardRead = withContext(dispatcher) {
        delay(latency)
        val failure = when {
            mode.isChip && card.type == SimCardType.MAGSTRIPE_ONLY -> EntryFailure.CARD_HAS_NO_CHIP
            mode.isChip && appNotRecognized -> EntryFailure.CHIP_APP_NOT_RECOGNIZED
            mode == EbtEntryMode.CHIP_TAP && card.type == SimCardType.CHIP -> EntryFailure.MODE_NOT_SUPPORTED
            mode.isChip && scenario == EbtSimScenario.CHIP_FAILS_ONCE -> {
                scenario = EbtSimScenario.NORMAL
                EntryFailure.CHIP_READ_FAILED
            }
            else -> null
        }
        if (failure != null) return@withContext CardRead.Failed(failure)
        val handle = "SIMREAD-" + UUID.randomUUID().toString().take(REF_LENGTH)
        lock.withLock { reads[handle] = mode }
        CardRead.Ready(handle, mode)
    }

    override suspend fun purchase(request: EbtPurchaseRequest): EbtPurchaseResult = withContext(dispatcher) {
        delay(latency)
        if (!network.value) return@withContext EbtPurchaseResult.Unknown
        val drop = scenario == EbtSimScenario.NETWORK_DROP_AFTER_SUBMIT
        val (record, balance) = lock.withLock {
            require(request.read.handle in reads) { "Unknown card read handle." }
            val record = processed[request.idempotencyKey.value] ?: process(request)
            record to EbtBalance(card.currency, snapCents, cashCents)
        }
        if (drop) {
            scenario = EbtSimScenario.NORMAL
            network.value = false
            return@withContext EbtPurchaseResult.Unknown
        }
        if (record.approved) {
            EbtPurchaseResult.Approved(record.engineReference, Money(record.approvedCents, record.currency), balance)
        } else {
            EbtPurchaseResult.Declined(record.engineReference, record.declineCode)
        }
    }

    override suspend fun queryStatus(key: IdempotencyKey): EngineStatus = withContext(dispatcher) {
        delay(latency)
        if (!network.value) return@withContext EngineStatus.Unreachable
        val r = lock.withLock { processed[key.value] } ?: return@withContext EngineStatus.NotProcessed
        if (r.approved) {
            EngineStatus.Authorized(r.engineReference, Money(r.approvedCents, r.currency).takeIf { r.approvedCents < r.requestedCents })
        } else {
            EngineStatus.Declined(r.engineReference, r.declineCode)
        }
    }

    /** Everything the simulated processor processed, oldest first. */
    public suspend fun processed(): List<SimProcessed> = lock.withLock { processed.values.toList() }

    /** Restores the starting balances, clears processor memory and scenarios, and reconnects. */
    public suspend fun reset(): Unit = lock.withLock {
        processed.clear()
        reads.clear()
        snapCents = startCard.snapCents
        cashCents = startCard.cashCents
        scenario = EbtSimScenario.NORMAL
        appNotRecognized = false
        network.value = true
    }

    /** Must be called with [lock] held. */
    private fun process(request: EbtPurchaseRequest): SimProcessed {
        val requested = request.amount.amountCents
        val available = if (request.tender == Tender.EBT_SNAP) snapCents else cashCents
        val approved = minOf(requested, available)
        val ref = "SIM-" + UUID.randomUUID().toString().take(REF_LENGTH).uppercase()
        if (approved > 0) {
            if (request.tender == Tender.EBT_SNAP) snapCents -= approved else cashCents -= approved
        }
        return SimProcessed(
            idempotencyKey = request.idempotencyKey.value,
            tender = request.tender,
            requestedCents = requested,
            approvedCents = approved,
            currency = request.amount.currency,
            engineReference = ref,
            declineCode = if (approved > 0) null else DECLINE_INSUFFICIENT_FUNDS,
        ).also { processed[it.idempotencyKey] = it }
    }

    /** Plain builder. No DI framework needed. */
    public class Builder {
        private var latency: Duration = Duration.ZERO
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO
        private var card: SimEbtCard = Cards.CHIP_CONTACTLESS

        /** Simulated latency per call. Defaults to zero. */
        public fun latency(latency: Duration): Builder = apply {
            require(!latency.isNegative()) { "latency must not be negative." }
            this.latency = latency
        }

        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        public fun card(card: SimEbtCard): Builder = apply { this.card = card }

        public fun build(): EbtSimulator = EbtSimulator(latency, dispatcher, card)
    }

    /** Fictional cards for demos and tests. */
    public object Cards {
        public val CHIP_CONTACTLESS: SimEbtCard = SimEbtCard("Chip + tap card (fictional)", SimCardType.CHIP_CONTACTLESS, snapCents = 5000, cashCents = 2000)
        public val CHIP_ONLY: SimEbtCard = SimEbtCard("Chip-only card (fictional)", SimCardType.CHIP, snapCents = 5000, cashCents = 2000)
        public val MAGSTRIPE_ONLY: SimEbtCard = SimEbtCard("Magstripe-only card (fictional)", SimCardType.MAGSTRIPE_ONLY, snapCents = 5000, cashCents = 2000)
        public val EMPTY_SNAP: SimEbtCard = SimEbtCard("Card with no SNAP balance (fictional)", SimCardType.CHIP_CONTACTLESS, snapCents = 0, cashCents = 0)
    }

    public companion object {
        /** Label every screen using the simulator must show (CLAUDE.md §9). */
        public const val LABEL: String = "DEMO — NOT A REAL EBT TRANSACTION"

        /** Simulated decline code for an empty balance. Not a real processor code. */
        public const val DECLINE_INSUFFICIENT_FUNDS: String = "SIM-NSF"

        private const val REF_LENGTH = 8
    }
}
