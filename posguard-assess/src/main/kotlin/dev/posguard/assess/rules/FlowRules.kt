package dev.posguard.assess.rules

import dev.posguard.assess.AssessRule
import dev.posguard.assess.Ease
import dev.posguard.assess.MasvsCategory
import dev.posguard.assess.Risk
import dev.posguard.assess.RuleContext
import dev.posguard.assess.RuleHit

/** Payments start with no device or app integrity check anywhere in the project. */
internal object NoPreflightIntegrity : AssessRule {
    override val id = "no-preflight-integrity"
    override val title = "No integrity check before taking payments"
    override val masvs = MasvsCategory.RESILIENCE
    override val defaultRisk = Risk.MEDIUM
    override val defaultEase = Ease.MODERATE
    override val posguardModule = "posguard-integrity"
    override val whyItMatters =
        "On a rooted, tampered, or remotely controlled phone, an attacker can watch or drive the checkout. The certified " +
            "engine protects card data, but the merchant app around it decides whether to open payment acceptance at all. " +
            "Heuristic: no Play Integrity, root check, or posguard preflight call was found anywhere."
    override val howToFix =
        "Check device and app integrity before opening the payment screen, and fail safe when the check is unavailable. " +
            "posguard's preflight() combines Play Integrity, overlay, screen-capture, accessibility, and root signals " +
            "into Allow, Warn, or Block."

    private val INTEGRITY = Regex(
        """\bpreflight\s*\(|IntegrityManager|IntegrityTokenRequest|\bPosGuard\b|\bisRooted\b|\bisDeviceRooted\b|RootBeer""",
    )

    override fun check(context: RuleContext): List<RuleHit> {
        val project = context.project
        val (file, line) = project.matches(Heuristics.PAYMENT_START, project.codeFiles).firstOrNull() ?: return emptyList()
        if (project.anyMatch(INTEGRITY, project.codeFiles)) return emptyList()
        return listOf(RuleHit(file.path, line, "Payments start here, and no integrity check was found in the project."))
    }
}

/** Payments are submitted without an idempotency key, or pending payments aren't recovered on start. */
internal object NoIdempotencyKey : AssessRule {
    override val id = "no-idempotency-key"
    override val title = "Payment flow isn't crash- or outage-safe"
    override val masvs = MasvsCategory.CODE
    override val defaultRisk = Risk.HIGH
    override val defaultEase = Ease.HARD
    override val posguardModule = "posguard-ledger"
    override val whyItMatters =
        "If the network drops or the app crashes after a payment is submitted, the app can't tell whether the customer " +
            "was charged. Retrying without the same idempotency key can charge twice, and giving up can lose a sale. " +
            "Heuristic: matched by names such as idempotency, clientTransactionId, recoverPending, or queryStatus."
    override val howToFix =
        "Give every payment an idempotency key before submitting it, journal its state, and on app start and reconnect " +
            "ask the engine about pending payments by that key before retrying. posguard-ledger implements this state machine."

    private val IDEMPOTENCY = Regex(
        """(?i)idempoten|clientTransactionId|clientReference|externalReference|uniqueTransactionId|\bTransactionLedger\b|\bTxnId\b""",
    )
    private val RECOVERY = Regex(
        """\brecoverPending\s*\(|\b(?:recover|reconcile|resume|resolve)\w*(?:Payment|Transaction|Txn|Pending)\w*\s*\(|\bqueryStatus\s*\(|\bgetTransactionStatus\s*\(""",
    )

    override fun check(context: RuleContext): List<RuleHit> {
        val project = context.project
        val (file, line) = project.matches(Heuristics.PAYMENT_START, project.codeFiles).firstOrNull() ?: return emptyList()
        return when {
            !project.anyMatch(IDEMPOTENCY, project.codeFiles) ->
                listOf(RuleHit(file.path, line, "Payments start here, and no idempotency key was found in the project."))
            !project.anyMatch(RECOVERY, project.codeFiles) ->
                listOf(RuleHit(file.path, line, "Payments start here, and pending payments are never recovered or re-queried."))
            else -> emptyList()
        }
    }
}

/** EBT code that offers magstripe before (or without) a chip attempt. */
internal object EbtMagstripeFirst : AssessRule {
    override val id = "ebt-magstripe-first"
    override val title = "EBT checkout may swipe before trying the chip"
    override val masvs = MasvsCategory.PLATFORM
    override val defaultRisk = Risk.HIGH
    override val defaultEase = Ease.MODERATE
    override val posguardModule = "posguard-ebt"
    override val whyItMatters =
        "Magnetic stripes are what skimmers copy. USDA's proposed SNAP EBT card security rule (91 FR 66958) would not " +
            "permit a magstripe transaction until at least one chip attempt has failed. A checkout that swipes first " +
            "keeps the skimmable path open. Heuristic: in files that mention EBT, magstripe or swipe appears before any chip " +
            "or tap reference."
    override val howToFix =
        "Prompt chip (insert or tap) first and offer magstripe only after a failed chip attempt or when the card or " +
            "reader can't use the chip. posguard-ebt's EbtEntryFlow enforces this order and records why each fallback happened."

    private val EBT = Regex("""\bebt|Ebt|EBT""")
    private val MAG_WORDS = setOf("swipe", "swiped", "swiping", "magstripe", "msr")
    private val CHIP_WORDS = setOf("chip", "emv", "insert", "icc", "contactless", "tap", "nfc")

    private fun firstWord(lines: List<String>, isMatch: (List<String>) -> Boolean): Pair<Int, Int>? {
        lines.forEachIndexed { index, line ->
            Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(line).forEach { m ->
                val w = Heuristics.words(m.value)
                if (isMatch(w)) return index to m.range.first
            }
        }
        return null
    }

    override fun check(context: RuleContext): List<RuleHit> = context.project.codeFiles
        .filter { it.containsCode(EBT) }
        .mapNotNull { file ->
            val mag = firstWord(file.code) { w -> w.any { it in MAG_WORDS } || w.zipWithNext().any { it == "mag" to "stripe" } }
                ?: return@mapNotNull null
            val chip = firstWord(file.code) { w -> w.any { it in CHIP_WORDS } }
            if (chip != null && compareValuesBy(chip, mag, { it.first }, { it.second }) < 0) return@mapNotNull null
            RuleHit(file.path, mag.first + 1, "Magstripe or swipe appears before any chip or tap step in this EBT code.")
        }
}
