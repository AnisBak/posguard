package dev.posguard.mockengine

import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender

/**
 * A charge request to the mock engine. **NOT A REAL PAYMENT.** There are no
 * card fields: the mock simulates the card read entirely.
 */
public data class ChargeRequest(
    val amount: Money,
    val tender: Tender,
    val idempotencyKey: IdempotencyKey,
)

/** Failure modes the mock engine can simulate (CLAUDE.md §9). */
public enum class MockScenario {
    /** The processor approves, and the app gets `Authorized`. */
    APPROVE,

    /** The processor declines with fake code "05", and the app gets `Declined`. */
    DECLINE,

    /** The request never reaches the processor, and the app gets `Unknown`. Resubmitting is safe. */
    TIMEOUT,

    /**
     * The processor **approves**, but the connection drops before the response
     * arrives. The app gets `Unknown`, and the engine stays offline until
     * [MockPaymentEngine.reconnect].
     */
    NETWORK_DROP_AFTER_SUBMIT,

    /** The processor approves. The app gets `Authorized`, then the same result again on [MockPaymentEngine.lateResponses]. */
    DUPLICATE_RESPONSE,

    /** The processor approves, then the engine throws [MockEngineCrashException] before returning. */
    ENGINE_CRASH,
}

/** Thrown by [MockScenario.ENGINE_CRASH]. Simulated. */
public class MockEngineCrashException :
    RuntimeException("Mock payment engine crashed (simulated). DEMO — NOT A REAL PAYMENT.")

/** A response delivered again after the first one (see [MockScenario.DUPLICATE_RESPONSE]). */
public data class LateResponse(val idempotencyKey: IdempotencyKey, val result: EngineResult)

/**
 * What the simulated processor recorded for one idempotency key. Contains no
 * card data.
 */
public data class ProcessedCharge(
    val idempotencyKey: String,
    val approved: Boolean,
    val engineReference: String,
    val amountCents: Long,
    val currency: String,
    val declineCode: String? = null,
    /** How the simulated card was presented. */
    val tap: MockTapInfo = MockTapInfo(),
) {
    internal fun toResult(): EngineResult =
        if (approved) EngineResult.Authorized(engineReference) else EngineResult.Declined(engineReference, declineCode)
}

/** How a simulated payment was presented, as a real SoftPOS engine might report it. */
public enum class MockEntryMode {
    CONTACTLESS_CARD,
    CONTACTLESS_WALLET,
    CHIP,
}

/**
 * Simulated tap metadata. **NOT REAL.** Real engines expose similar
 * non-sensitive fields (entry mode, timings) in their own types. An adapter
 * maps them to posguard's `TapMetadata`, as the demo app does.
 */
public data class MockTapInfo(
    val entryMode: MockEntryMode = MockEntryMode.CONTACTLESS_CARD,
    val cardReadMillis: Long? = DEFAULT_CARD_READ_MILLIS,
) {
    public companion object {
        public const val DEFAULT_CARD_READ_MILLIS: Long = 350
    }
}
