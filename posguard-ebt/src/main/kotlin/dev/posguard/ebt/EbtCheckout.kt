package dev.posguard.ebt

import dev.posguard.core.event.GuardEvent
import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TxnId
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of one EBT leg. */
public sealed interface EbtLegResult {
    public val txnId: TxnId

    public data class Approved(
        override val txnId: TxnId,
        val tender: Tender,
        val requested: Money,
        val approved: Money,
        val entryMode: EbtEntryMode,
        val engineReference: String,
        /** For the customer receipt only. Never show it on the POS screen. */
        val balance: EbtBalance?,
    ) : EbtLegResult {
        public val isPartial: Boolean get() = approved.amountCents < requested.amountCents
    }

    public data class Declined(override val txnId: TxnId, val declineCode: String?) : EbtLegResult

    /** No mode could read the card. Nothing was sent to the processor, and the ledger entry is VOIDED. */
    public data class CardUnreadable(override val txnId: TxnId, val attempts: List<EntryAttempt>) : EbtLegResult

    /** No answer from the engine. The ledger holds it as UNKNOWN until recovery resolves it. Never retry blindly. */
    public data class Unknown(override val txnId: TxnId) : EbtLegResult
}

/**
 * Runs EBT legs through the outage-safe ledger and the certified engine.
 *
 * Each leg is its own ledger transaction with its own idempotency key, so the
 * ledger's crash and outage recovery covers EBT exactly as it covers cards.
 * Card-entry fallback follows [EbtConfig.entryOrder] via [EbtEntryFlow].
 * Card legs (the non-SNAP remainder) go through the app's normal card flow.
 *
 * Run [dev.posguard.core.PosGuard.preflight] before the first leg, as for any payment.
 */
public class EbtCheckout(
    private val ledger: TransactionLedger,
    private val engine: EbtEngine,
    private val config: EbtConfig,
    private val listeners: List<GuardEventListener> = emptyList(),
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Splits [lines] with the configured eligibility rules. */
    public fun split(lines: List<BasketLine>): BasketSplit = BasketSplitter.split(lines, config.eligibility)

    /**
     * Pays one EBT leg ([Tender.EBT_SNAP] or [Tender.EBT_CASH]). [onPrompt]
     * runs before each card read so the app can show "Insert card", "Tap card",
     * or the fallback message.
     *
     * An approved leg is left AUTHORIZED. Call `ledger.markCompleted` when
     * the sale is finished.
     */
    public suspend fun payLeg(
        leg: TenderLeg,
        onPrompt: suspend (EntryPrompt.Present) -> Unit = {},
    ): EbtLegResult {
        require(leg.tender == Tender.EBT_SNAP || leg.tender == Tender.EBT_CASH) { "payLeg is for EBT legs only." }
        val id = ledger.begin(leg.amount, leg.tender)
        val key = checkNotNull(ledger.get(id)).idempotencyKey

        val read = when (val r = readWithFallback(id, onPrompt)) {
            is ReadOutcome.Read -> r.ready
            is ReadOutcome.GaveUp -> {
                ledger.markVoided(id)
                return EbtLegResult.CardUnreadable(id, r.attempts)
            }
        }

        ledger.markSubmitted(id)
        val result = try {
            engine.purchase(EbtPurchaseRequest(leg.amount, leg.tender, key, read))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            EbtPurchaseResult.Unknown
        }

        return when (result) {
            is EbtPurchaseResult.Approved -> {
                val approved = result.approvedAmount
                if (approved.currency != leg.amount.currency || approved.amountCents > leg.amount.amountCents) {
                    // An impossible answer. Don't guess: let recovery ask the engine again.
                    ledger.markResult(id, EngineResult.Unknown)
                    return EbtLegResult.Unknown(id)
                }
                val partial = approved.amountCents < leg.amount.amountCents
                ledger.markResult(id, EngineResult.Authorized(result.engineReference, approved.takeIf { partial }))
                if (partial) notify(GuardEvent.EbtPartialApproval(id, clock.instant()))
                EbtLegResult.Approved(id, leg.tender, leg.amount, approved, read.mode, result.engineReference, result.balance)
            }
            is EbtPurchaseResult.Declined -> {
                ledger.markResult(id, EngineResult.Declined(result.engineReference, result.declineCode))
                EbtLegResult.Declined(id, result.declineCode)
            }
            EbtPurchaseResult.Unknown -> {
                ledger.markResult(id, EngineResult.Unknown)
                EbtLegResult.Unknown(id)
            }
        }
    }

    private sealed interface ReadOutcome {
        data class Read(val ready: CardRead.Ready) : ReadOutcome
        data class GaveUp(val attempts: List<EntryAttempt>) : ReadOutcome
    }

    private suspend fun readWithFallback(id: TxnId, onPrompt: suspend (EntryPrompt.Present) -> Unit): ReadOutcome {
        val flow = EbtEntryFlow(config.entryOrder)
        var prompt: EntryPrompt = flow.start()
        while (true) {
            val present = when (val p = prompt) {
                is EntryPrompt.Present -> p
                is EntryPrompt.Exhausted -> return ReadOutcome.GaveUp(p.attempts)
            }
            onPrompt(present)
            val read = try {
                engine.readCard(present.mode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CardRead.Failed(EntryFailure.READ_ERROR)
            }
            when (read) {
                is CardRead.Ready -> {
                    check(read.mode == present.mode) { "Engine read ${read.mode} but ${present.mode} was prompted." }
                    flow.onRead(present.mode)
                    return ReadOutcome.Read(read)
                }
                is CardRead.Failed -> {
                    prompt = flow.onFailed(present.mode, read.failure)
                    val next = (prompt as? EntryPrompt.Present)?.mode
                    notify(GuardEvent.EbtEntryFallback(id, present.mode.name, next?.name, read.failure.name, clock.instant()))
                }
            }
        }
    }

    private fun notify(event: GuardEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (_: Exception) {
                // A listener must never break checkout.
            }
        }
    }
}
