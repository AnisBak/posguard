package dev.posguard.mockengine

import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.EngineStatusSource
import dev.posguard.core.ledger.IdempotencyKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * **DEMO — NOT A REAL PAYMENT ENGINE.**
 *
 * A fake stand-in for a certified SoftPOS SDK, for tests and the demo app. It
 * never reads cards, never handles PINs, and never moves money. It simulates
 * a processor that deduplicates by idempotency key, plus the failure modes
 * in [MockScenario].
 *
 * Implements [EngineStatusSource] so `TransactionLedger.recoverPending()` can
 * look up earlier charges by key.
 */
public class MockPaymentEngine private constructor(
    private val store: MockProcessorStore,
    private val latency: Duration,
    private val dispatcher: CoroutineDispatcher,
    defaultScenario: MockScenario,
) : EngineStatusSource {

    private val processorLock = Mutex()
    private val nextScenario = AtomicReference<MockScenario?>(null)
    private val network = MutableStateFlow(true)
    private val late = MutableSharedFlow<LateResponse>(extraBufferCapacity = LATE_BUFFER)

    /** Scenario used when no one-shot [setNextScenario] is pending. */
    @Volatile
    public var defaultScenario: MockScenario = defaultScenario

    /** Whether the engine can reach its (simulated) processor. */
    public val networkAvailable: StateFlow<Boolean> = network.asStateFlow()

    /** Results delivered a second time ([MockScenario.DUPLICATE_RESPONSE]). Forward them to the ledger. */
    public val lateResponses: SharedFlow<LateResponse> = late.asSharedFlow()

    /** Uses [scenario] for the next [charge] only, then goes back to [defaultScenario]. */
    public fun setNextScenario(scenario: MockScenario) {
        nextScenario.set(scenario)
    }

    /** Simulates losing connectivity. */
    public fun disconnect() {
        network.value = false
    }

    /** Simulates connectivity coming back. */
    public fun reconnect() {
        network.value = true
    }

    /**
     * Simulated charge. Never charges a key twice: repeating a key returns the
     * original result (processor-side deduplication).
     *
     * @throws MockEngineCrashException for [MockScenario.ENGINE_CRASH].
     */
    public suspend fun charge(request: ChargeRequest): EngineResult = withContext(dispatcher) {
        val scenario = nextScenario.getAndSet(null) ?: defaultScenario
        delay(latency)
        // Offline: the request never reaches the processor.
        if (!network.value) return@withContext EngineResult.Unknown

        val key = request.idempotencyKey
        val existing = processorLock.withLock { store.get(key.value) }
        if (existing != null) return@withContext existing.toResult()

        when (scenario) {
            MockScenario.APPROVE -> process(request, approved = true).toResult()
            MockScenario.DECLINE -> process(request, approved = false).toResult()
            MockScenario.TIMEOUT -> EngineResult.Unknown
            MockScenario.NETWORK_DROP_AFTER_SUBMIT -> {
                process(request, approved = true)
                network.value = false
                EngineResult.Unknown
            }
            MockScenario.DUPLICATE_RESPONSE -> {
                val result = process(request, approved = true).toResult()
                late.tryEmit(LateResponse(key, result))
                result
            }
            MockScenario.ENGINE_CRASH -> {
                process(request, approved = true)
                throw MockEngineCrashException()
            }
        }
    }

    /** Status lookup by idempotency key, as the ledger uses during recovery. */
    override suspend fun queryStatus(key: IdempotencyKey): EngineStatus = withContext(dispatcher) {
        delay(latency)
        if (!network.value) return@withContext EngineStatus.Unreachable
        val charge = processorLock.withLock { store.get(key.value) } ?: return@withContext EngineStatus.NotProcessed
        if (charge.approved) {
            EngineStatus.Authorized(charge.engineReference)
        } else {
            EngineStatus.Declined(charge.engineReference, charge.declineCode)
        }
    }

    /** Everything the simulated processor has processed. The demo uses it to show "charged once". */
    public suspend fun processedCharges(): List<ProcessedCharge> =
        withContext(dispatcher) { processorLock.withLock { store.all() } }

    /** Clears processor memory and restores connectivity and scenarios. For "Reset demo". */
    public suspend fun reset(): Unit = withContext(dispatcher) {
        processorLock.withLock { store.clear() }
        nextScenario.set(null)
        network.value = true
    }

    private suspend fun process(request: ChargeRequest, approved: Boolean): ProcessedCharge =
        processorLock.withLock {
            // Re-check under the lock so two concurrent charges with one key process once.
            store.get(request.idempotencyKey.value) ?: ProcessedCharge(
                idempotencyKey = request.idempotencyKey.value,
                approved = approved,
                engineReference = "MOCK-" + UUID.randomUUID().toString().take(REF_LENGTH).uppercase(),
                amountCents = request.amount.amountCents,
                currency = request.amount.currency,
                declineCode = if (approved) null else FAKE_DECLINE_CODE,
            ).also(store::put)
        }

    /** Plain builder. No DI framework needed. */
    public class Builder {
        private var store: MockProcessorStore = InMemoryMockProcessorStore()
        private var latency: Duration = Duration.ZERO
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO
        private var defaultScenario: MockScenario = MockScenario.APPROVE

        /** Processor memory. Defaults to in-memory. Use [SharedPreferencesMockProcessorStore] in the demo. */
        public fun store(store: MockProcessorStore): Builder = apply { this.store = store }

        /** Simulated network latency per call. Defaults to zero. */
        public fun latency(latency: Duration): Builder = apply {
            require(!latency.isNegative()) { "latency must not be negative." }
            this.latency = latency
        }

        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        public fun defaultScenario(scenario: MockScenario): Builder = apply { defaultScenario = scenario }

        public fun build(): MockPaymentEngine = MockPaymentEngine(store, latency, dispatcher, defaultScenario)
    }

    public companion object {
        /** Label every screen using the mock engine must show (CLAUDE.md §9). */
        public const val LABEL: String = "DEMO — NOT A REAL PAYMENT"

        /** Fake decline code used by [MockScenario.DECLINE]. */
        public const val FAKE_DECLINE_CODE: String = "05"

        private const val REF_LENGTH = 8
        private const val LATE_BUFFER = 16
    }
}
