package dev.posguard.assess.rules

import dev.posguard.assess.AssessRule
import dev.posguard.assess.Ease
import dev.posguard.assess.MasvsCategory
import dev.posguard.assess.Risk
import dev.posguard.assess.RuleContext
import dev.posguard.assess.RuleHit

/** Logging calls that mention card data or PIN variables. */
internal object CardDataInLogs : AssessRule {
    override val id = "card-data-in-logs"
    override val title = "Card data or PIN may be written to logs"
    override val masvs = MasvsCategory.PRIVACY
    override val defaultRisk = Risk.HIGH
    override val defaultEase = Ease.EASY
    override val posguardModule = "posguard-telemetry"
    override val whyItMatters =
        "Logs end up in logcat, crash reports, and support bundles. A card number, track data, CVV, or PIN in a log " +
            "is cardholder data leaving the protected payment engine. Heuristic: matched by variable names such as " +
            "pan, cardNumber, track2, cvv, or pinBlock inside a logging call."
    override val howToFix =
        "Remove the value from the log call. Log a transaction id instead. Route app events through a scrubbing " +
            "reporter such as posguard-telemetry, which drops card-like numbers before anything is stored or sent."

    private val LOG_CALL = Regex(
        """\b(?:Log\.(?:v|d|i|w|e|wtf)|Timber(?:\.tag\([^)]*\))?\.(?:v|d|i|w|e|wtf)|println|print|System\.(?:out|err)\.print(?:ln|f)?|(?:logger|LOGGER|log|LOG)\.(?:trace|debug|info|warn|warning|error|fine|severe|log))\s*\(""",
    )

    override fun check(context: RuleContext): List<RuleHit> = context.project.codeFiles.flatMap { file ->
        file.code.mapIndexedNotNull { index, line ->
            val call = LOG_CALL.find(line) ?: return@mapIndexedNotNull null
            val name = Heuristics.identifiers(line.substring(call.range.last + 1)).firstOrNull(Heuristics::isCardDataName)
                ?: return@mapIndexedNotNull null
            RuleHit(file.path, index + 1, "Logging call references `$name`.")
        }
    }
}

/** Payment state or card data written to SharedPreferences or plain files. */
internal object PaymentStatePlainPrefs : AssessRule {
    override val id = "payment-state-plain-prefs"
    override val title = "Payment state stored in plain preferences or files"
    override val masvs = MasvsCategory.STORAGE
    override val defaultRisk = Risk.MEDIUM
    override val defaultEase = Ease.MODERATE
    override val posguardModule = "posguard-ledger"
    override val whyItMatters =
        "SharedPreferences and app files are plain text on disk, can be included in backups, and aren't a crash-safe " +
            "journal: a half-written update can lose a payment or cause a double charge. Card data must never be stored " +
            "by the app at all. Heuristic: matched by key names such as txn, payment, pending, amount, or card."
    override val howToFix =
        "Keep transaction state in a transactional store with explicit states and idempotency keys (posguard-ledger " +
            "uses Room). Never store card numbers, track data, CVV, or PINs. Turn off backup for payment data."

    private val PUT_LITERAL = Regex("""\.put(?:String|Long|Int|Boolean|Float|StringSet)\s*\(\s*"([^"]+)"""")
    private val PUT_CONSTANT = Regex("""\.put(?:String|Long|Int|Boolean|Float|StringSet)\s*\(\s*(?:\w+\.)?([A-Z][A-Z0-9_]+)\s*,""")
    private val FILE_LITERAL = Regex("""\bopenFileOutput\s*\(\s*"([^"]+)"""")
    private val ENCRYPTED = Regex("""EncryptedSharedPreferences|EncryptedFile""")

    override fun check(context: RuleContext): List<RuleHit> = context.project.codeFiles
        .filterNot { it.containsCode(ENCRYPTED) }
        .flatMap { file ->
            file.code.mapIndexedNotNull { index, line ->
                val prefKeys = (PUT_LITERAL.findAll(line) + PUT_CONSTANT.findAll(line))
                    .map { it.groupValues[1] }
                    .filter(Heuristics::isPaymentStateName)
                    .toList()
                val fileName = FILE_LITERAL.find(line)?.groupValues?.get(1)
                when {
                    prefKeys.any(Heuristics::isCardDataName) ->
                        RuleHit(file.path, index + 1, "Writes a card-data-like key to SharedPreferences.")
                    prefKeys.isNotEmpty() -> RuleHit(file.path, index + 1, "Writes payment state to SharedPreferences.")
                    fileName != null && Heuristics.isPaymentStateName(fileName) ->
                        RuleHit(file.path, index + 1, "Writes payment state to a plain app file.")
                    else -> null
                }
            }
        }
}
