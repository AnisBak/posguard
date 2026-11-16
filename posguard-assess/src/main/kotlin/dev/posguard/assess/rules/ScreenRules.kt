package dev.posguard.assess.rules

import dev.posguard.assess.AssessRule
import dev.posguard.assess.Ease
import dev.posguard.assess.MasvsCategory
import dev.posguard.assess.Risk
import dev.posguard.assess.RuleContext
import dev.posguard.assess.RuleHit

/** Payment screen without `FLAG_SECURE`. */
internal object PaymentScreenNoFlagSecure : AssessRule {
    override val id = "payment-screen-no-flag-secure"
    override val title = "Payment screen can be captured or recorded"
    override val masvs = MasvsCategory.PLATFORM
    override val defaultRisk = Risk.HIGH
    override val defaultEase = Ease.EASY
    override val posguardModule = "posguard-integrity"
    override val whyItMatters =
        "Without the secure window flag, screenshots, screen recording, casting, and the recent-apps thumbnail can capture " +
            "the checkout, including amounts and anything the engine draws in the app's window. Heuristic: a screen is " +
            "matched by name, and the flag may be set in a base class this rule can't see."
    override val howToFix =
        "Set WindowManager.LayoutParams.FLAG_SECURE while the payment screen is shown (all API levels), or call " +
            "PaymentScreenProtection.protect(activity) from posguard-integrity."

    private val SECURE = Regex("""FLAG_SECURE|setSecure\s*\(|PaymentScreenProtection|SecureFlagPolicy\.SecureOn""")

    override fun check(context: RuleContext): List<RuleHit> =
        Heuristics.paymentScreens(context.project)
            .filterNot { it.file.containsCode(SECURE) }
            .map { RuleHit(it.file.path, it.line, "${it.name} doesn't set FLAG_SECURE in this file.") }
}

/** Payment screen without obscured-touch filtering. */
internal object PaymentScreenNoTouchFilter : AssessRule {
    override val id = "payment-screen-no-touch-filter"
    override val title = "Payment screen accepts touches through overlays"
    override val masvs = MasvsCategory.PLATFORM
    override val defaultRisk = Risk.HIGH
    override val defaultEase = Ease.EASY
    override val posguardModule = "posguard-integrity"
    override val whyItMatters =
        "An overlay app can draw over the checkout and trick the user into tapping (tapjacking), for example to confirm " +
            "an amount they can't see. Heuristic: a screen is matched by name, and filtering may live in a base class."
    override val howToFix =
        "Set filterTouchesWhenObscured on the payment views (or android:filterTouchesWhenObscured=\"true\" in the layout), " +
            "and treat MotionEvent.FLAG_WINDOW_IS_OBSCURED as a warning sign. PaymentScreenProtection in " +
            "posguard-integrity does this and reports overlays to preflight."

    private val FILTER = Regex(
        """filterTouchesWhenObscured|setFilterTouchesWhenObscured|FLAG_WINDOW_IS_OBSCURED|FLAG_WINDOW_IS_PARTIALLY_OBSCURED|PaymentScreenProtection|ObscuredTouch""",
    )
    private val XML_FILTER = Regex("""filterTouchesWhenObscured\s*=\s*"true"""")

    override fun check(context: RuleContext): List<RuleHit> =
        Heuristics.paymentScreens(context.project)
            .filterNot { screen ->
                screen.file.containsCode(FILTER) ||
                    Heuristics.layoutsReferenced(screen.file).any { layout ->
                        Heuristics.layoutFiles(context.project, layout).any { it.containsCode(XML_FILTER) }
                    }
            }
            .map { RuleHit(it.file.path, it.line, "${it.name} doesn't filter touches from obscured windows.") }
}
