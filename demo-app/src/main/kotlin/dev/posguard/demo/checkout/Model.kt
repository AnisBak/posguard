package dev.posguard.demo.checkout

import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.verdict.GuardVerdict

/** Fictional Sunrise Market catalog. Prices in cents (USD). */
data class Product(val id: String, val name: String, val emoji: String, val priceCents: Long)

object Catalog {
    val products: List<Product> = listOf(
        Product("coffee", "Coffee", "☕", 250),
        Product("bagel", "Bagel", "🥯", 325),
        Product("bananas", "Bananas", "🍌", 199),
        Product("juice", "Orange juice", "🧃", 399),
        Product("sandwich", "Sandwich", "🥪", 750),
        Product("muffin", "Muffin", "🧁", 299),
    )

    fun byId(id: String): Product? = products.firstOrNull { it.id == id }
}

/** Demo-controls scenarios (CLAUDE.md §10, plus extra engine failure modes). */
enum class DemoScenario(val label: String) {
    NORMAL("1 · Normal payment"),
    OVERLAY("2 · Overlay app present"),
    RECORDING("3 · Screen recording active"),
    NETWORK_DROP("4 · Network drops after submit"),
    APP_CRASH("5 · App crashes mid-payment"),
    RELAY_BURST("7 · Burst of wallet taps"),
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
