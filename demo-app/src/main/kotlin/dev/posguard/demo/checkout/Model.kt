package dev.posguard.demo.checkout

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.ebt.BasketLine
import dev.posguard.ebt.BasketSplit
import dev.posguard.ebt.EbtLegResult
import dev.posguard.ebt.TenderLeg

/**
 * Fictional Sunrise Market catalog. Prices in cents (USD). [category] is looked
 * up in the EBT eligibility config (assets/demo-ebt.properties), never decided here.
 */
data class Product(val id: String, val name: String, val emoji: String, val priceCents: Long, val category: String)

object Catalog {
    val products: List<Product> = listOf(
        Product("coffee", "Hot coffee", "☕", 250, "hot_food"),
        Product("bagel", "Bagel", "🥯", 325, "bakery"),
        Product("bananas", "Bananas", "🍌", 199, "produce"),
        Product("juice", "Orange juice", "🧃", 399, "beverages"),
        Product("sandwich", "Hot sandwich", "🥪", 750, "hot_food"),
        Product("muffin", "Muffin", "🧁", 299, "bakery"),
        Product("soda", "Soda", "🥤", 199, "soda"),
        Product("soap", "Dish soap", "🧼", 425, "household"),
    )

    fun byId(id: String): Product? = products.firstOrNull { it.id == id }

    /** The cart as EBT basket lines: item data only. */
    fun basket(cart: Map<String, Int>): List<BasketLine> = cart.mapNotNull { (id, qty) ->
        byId(id)?.let { BasketLine(it.id, it.category, Money(it.priceCents, "USD"), qty) }
    }
}

/** Demo-controls scenarios (CLAUDE.md §10, plus extra engine failure modes). */
enum class DemoScenario(val label: String) {
    NORMAL("1 · Normal payment"),
    OVERLAY("2 · Overlay app present"),
    RECORDING("3 · Screen recording active"),
    NETWORK_DROP("4 · Network drops after submit"),
    APP_CRASH("5 · App crashes mid-payment"),
    RELAY_BURST("7 · Burst of wallet taps"),
    EBT_PURCHASE("9 · EBT purchase (chip fails → tap, partial)"),
    EBT_MAGSTRIPE_CARD("EBT: magstripe-only card"),
    EBT_CHIP_APP_UNKNOWN("EBT: reader lacks SNAP chip app"),
    EBT_NO_BALANCE("EBT: no SNAP balance"),
    DECLINE("Card declined"),
    ENGINE_CRASH("Payment engine crash"),
    TIMEOUT("Engine timeout (nothing charged)"),
    DUPLICATE_RESPONSE("Duplicate engine response"),
    DEVICE_INTEGRITY_FAIL("Play Integrity: device fails"),
}

sealed interface StartupState {
    data object Recovering : StartupState
    data object Ready : StartupState

    /** Payments the engine could not confirm yet. New payments wait until they resolve. */
    data class Pending(val unresolved: Int) : StartupState
}

sealed interface PaymentPhase {
    data object ReadyToTap : PaymentPhase
    data object Processing : PaymentPhase
    data class Approved(val engineReference: String?, val processorCharges: Int) : PaymentPhase
    data class Declined(val declineCode: String?) : PaymentPhase

    /** Result unknown (connection lost after submit or engine crash). Never retried blindly. */
    data class Unknown(val txnId: TxnId) : PaymentPhase

    /** The engine confirmed nothing was processed. Resubmitting with the same key is safe. */
    data class SafeToResubmit(val txnId: TxnId) : PaymentPhase

    /**
     * Approved by the engine, but experimental relay-risk rules flagged it. The
     * ledger keeps it AUTHORIZED until the merchant reviews it. Never declined.
     */
    data class HeldForReview(
        val txnId: TxnId,
        val reasons: List<String>,
        val engineReference: String?,
        val processorCharges: Int,
    ) : PaymentPhase

    // --- EBT (scenario 9). Simulator only: no card data, no PIN in this app. ---

    /** The basket split into SNAP-eligible and other items, before any card is read. */
    data class EbtSplit(val split: BasketSplit, val legs: List<TenderLeg>) : PaymentPhase

    /** Card entry in progress. [log] lists prompts and fallbacks so far. */
    data class EbtProcessing(val log: List<String>) : PaymentPhase

    /** SNAP paid its part. [remaining] (non-eligible items, plus any shortfall) goes to card next. */
    data class EbtRemainder(val snap: EbtLegResult.Approved, val remaining: TenderLeg, val log: List<String>) : PaymentPhase

    /** Sale finished. [receiptLines] is the screen version: balances are masked. */
    data class EbtDone(val receiptLines: List<String>, val log: List<String>) : PaymentPhase

    /** The EBT leg didn't go through (declined or unreadable). Nothing was charged to SNAP. */
    data class EbtFailed(val title: String, val body: String, val log: List<String>) : PaymentPhase
}

sealed interface Screen {
    data object Shop : Screen

    /** Preflight said Block (no continue) or Warn (continue allowed). */
    data class Flagged(val verdict: GuardVerdict) : Screen {
        val canContinue: Boolean get() = verdict is GuardVerdict.Warn
    }

    data class Payment(val amount: Money, val phase: PaymentPhase, val verdict: GuardVerdict) : Screen
}

data class UiState(
    val cart: Map<String, Int> = emptyMap(),
    val screen: Screen = Screen.Shop,
    val startup: StartupState = StartupState.Recovering,
    val banner: String? = null,
    val busy: Boolean = false,
    val armed: Set<DemoScenario> = emptySet(),
) {
    val totalCents: Long get() = cart.entries.sumOf { (id, qty) -> (Catalog.byId(id)?.priceCents ?: 0) * qty }
    val canTakePayment: Boolean get() = startup == StartupState.Ready && totalCents > 0 && !busy
}

/** Remembers when "Reset demo" was pressed, so history shows only newer payments. */
interface ResetStore {
    var resetAtMillis: Long
}

fun formatCents(cents: Long): String = "$" + "%d.%02d".format(cents / 100, cents % 100)
