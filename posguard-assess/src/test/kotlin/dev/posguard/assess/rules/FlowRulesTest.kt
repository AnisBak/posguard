package dev.posguard.assess.rules

import dev.posguard.assess.hits
import dev.posguard.assess.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowRulesTest {

    private val legacyCheckout = source(
        "Checkout.kt",
        """
        class Checkout(private val sdk: PaymentSdk) {
            fun pay(amount: Long) {
                sdk.startPayment(amount)
            }
        }
        """,
    )

    @Test
    fun `flags payments with no integrity check, at the first payment start`() {
        val hit = NoPreflightIntegrity.hits(legacyCheckout).single()
        assertEquals("Checkout.kt", hit.file)
        assertEquals(3, hit.line)
    }

    @Test
    fun `integrity check anywhere in the project satisfies the rule`() {
        val guard = source("Guard.kt", "suspend fun open() { val v = posGuard.preflight() }")
        val play = source("Play.kt", "val m = IntegrityManagerFactory.createStandard(ctx)")
        assertEquals(emptyList<Any>(), NoPreflightIntegrity.hits(legacyCheckout, guard))
        assertEquals(emptyList<Any>(), NoPreflightIntegrity.hits(legacyCheckout, play))
    }

    @Test
    fun `no payment code means no flow findings`() {
        val other = source("Menu.kt", "class Menu { fun show() {} }")
        assertEquals(emptyList<Any>(), NoPreflightIntegrity.hits(other))
        assertEquals(emptyList<Any>(), NoIdempotencyKey.hits(other))
    }

    @Test
    fun `flags missing idempotency key, then missing recovery`() {
        assertEquals("Payments start here, and no idempotency key was found in the project.", NoIdempotencyKey.hits(legacyCheckout).single().detail)

        val keyed = source("Req.kt", "data class ChargeRequest(val amount: Long, val idempotencyKey: String)")
        assertEquals(
            "Payments start here, and pending payments are never recovered or re-queried.",
            NoIdempotencyKey.hits(legacyCheckout, keyed).single().detail,
        )

        val recovering = source("App.kt", "override fun onCreate() { scope.launch { ledger.recoverPending() } }")
        assertEquals(emptyList<Any>(), NoIdempotencyKey.hits(legacyCheckout, keyed, recovering))
    }

    @Test
    fun `flags EBT code that swipes before any chip step`() {
        val swipeFirst = source(
            "EbtTender.kt",
            """
            class EbtTender {
                fun pay() {
                    reader.swipeCard()
                    reader.insertChip()
                }
            }
            """,
        )
        assertEquals(3, EbtMagstripeFirst.hits(swipeFirst).single().line)

        val swipeOnly = source("SnapPay.java", "class SnapPay { void ebt() { msr.read(); } }")
        assertEquals(1, EbtMagstripeFirst.hits(swipeOnly).size)

        val ebtOnlyInComment = source("Order.kt", "val order = listOf(MAGSTRIPE, CHIP_INSERT, CHIP_TAP) // ebt")
        assertEquals(emptyList<Any>(), EbtMagstripeFirst.hits(ebtOnlyInComment))
        val sameLineEbt = source("EbtOrder2.kt", "val ebtOrder = listOf(MAGSTRIPE, CHIP_INSERT, CHIP_TAP)")
        assertEquals(1, EbtMagstripeFirst.hits(sameLineEbt).size)
    }

    @Test
    fun `accepts chip-first EBT code and ignores non-EBT swipes`() {
        val chipFirst = source(
            "EbtTender.kt",
            """
            val ebtOrder = listOf(CHIP_INSERT, CHIP_TAP, MAGSTRIPE)
            fun fallback() { reader.swipeCard() }
            """,
        )
        assertEquals(emptyList<Any>(), EbtMagstripeFirst.hits(chipFirst))
        val card = source("Card.kt", "fun pay() { reader.swipeCard() } // debt is not ebt here")
        assertEquals(emptyList<Any>(), EbtMagstripeFirst.hits(card))
        val cardTypes = source(
            "EbtCards.kt",
            """
            enum class EbtCardType {
                MAGSTRIPE_ONLY,
                CHIP,
            }
            enum class EbtScenario(val label: String) { EBT_MAGSTRIPE_CARD("x"), EBT_OTHER("y") }
            fun prompt() = show("Swipe card") // string text is not an action
            """,
        )
        assertEquals(emptyList<Any>(), EbtMagstripeFirst.hits(cardTypes))
    }
}
