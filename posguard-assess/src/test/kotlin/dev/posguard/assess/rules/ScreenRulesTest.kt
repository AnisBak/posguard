package dev.posguard.assess.rules

import dev.posguard.assess.hits
import dev.posguard.assess.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRulesTest {

    private val bareActivity = source(
        "app/src/main/java/shop/PaymentActivity.kt",
        """
        package shop
        // FLAG_SECURE and filterTouchesWhenObscured only appear in this comment
        class PaymentActivity : AppCompatActivity() {
            override fun onCreate(b: Bundle?) { setContentView(R.layout.activity_payment) }
        }
        """,
    )

    @Test
    fun `flags a payment activity without FLAG_SECURE`() {
        val hits = PaymentScreenNoFlagSecure.hits(bareActivity)
        assertEquals(1, hits.size)
        assertEquals("app/src/main/java/shop/PaymentActivity.kt", hits[0].file)
        assertEquals(3, hits[0].line)
        assertTrue(hits[0].detail.startsWith("PaymentActivity"))
    }

    @Test
    fun `accepts FLAG_SECURE or PaymentScreenProtection`() {
        val secured = source(
            "CheckoutActivity.java",
            """
            public class CheckoutActivity extends Activity {
                void onCreate() { getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE); }
            }
            """,
        )
        val compose = source(
            "Ui.kt",
            """
            @Composable
            private fun PaymentScreen() { DisposableEffect(Unit) { PaymentScreenProtection.protect(activity); onDispose {} } }
            """,
        )
        assertEquals(emptyList<Any>(), PaymentScreenNoFlagSecure.hits(secured, compose))
        assertEquals(emptyList<Any>(), PaymentScreenNoTouchFilter.hits(compose))
    }

    @Test
    fun `matches payment screens by name only`() {
        val others = source(
            "Other.kt",
            """
            class DisplayActivity : Activity()
            class PayloadParser
            class CheckoutController
            object PaymentScreenHelper
            fun PayScreen() {}
            data class PaymentScreen(val amount: Long) : UiState
            sealed class CheckoutScreen : Route
            class PaymentDialog
            val s = "class PaymentActivity : Activity()"
            """,
        )
        val hits = PaymentScreenNoFlagSecure.hits(others)
        assertEquals(listOf(5), hits.map { it.line })
    }

    @Test
    fun `flags missing touch filter unless code or referenced layout sets it`() {
        assertEquals(1, PaymentScreenNoTouchFilter.hits(bareActivity).size)

        val layout = source(
            "app/src/main/res/layout/activity_payment.xml",
            """<Button android:filterTouchesWhenObscured="true" />""",
        )
        assertEquals(emptyList<Any>(), PaymentScreenNoTouchFilter.hits(bareActivity, layout))

        val wrongDir = source("app/src/main/res/values/activity_payment.xml", """<x android:filterTouchesWhenObscured="true"/>""")
        assertEquals(1, PaymentScreenNoTouchFilter.hits(bareActivity, wrongDir).size)

        val code = source("PayFragment.kt", "class PayFragment : Fragment() { init { view.filterTouchesWhenObscured = true } }")
        assertEquals(emptyList<Any>(), PaymentScreenNoTouchFilter.hits(code))
    }
}
