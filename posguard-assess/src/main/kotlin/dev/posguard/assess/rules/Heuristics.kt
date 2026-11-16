package dev.posguard.assess.rules

import dev.posguard.assess.ProjectView
import dev.posguard.assess.SourceFile

/** A payment screen declaration found in code. */
internal data class PaymentScreen(val file: SourceFile, val line: Int, val name: String)

/** Text patterns shared by several rules. All of them are heuristics. */
internal object Heuristics {

    /**
     * `class PaymentActivity : ...`, `fun CheckoutScreen(`, `class PayFragment extends ...`. Data, sealed, enum,
     * annotation, and value classes are models, not screens, and classes must extend something.
     */
    private val PAYMENT_SCREEN = Regex(
        """(?:(?<!data |sealed |enum |annotation |value |inline )\bclass\s+(\w*(?:Payment|Checkout|Tender|Charge|Pay(?=[A-Z]))\w*(?:Activity|Fragment|Screen|Dialog))\b(?=[^{]*(?::|\bextends\b))""" +
            """|\bfun\s+(\w*(?:Payment|Checkout|Tender|Charge|Pay(?=[A-Z]))\w*(?:Screen|Dialog))\s*\()""",
    )

    private val STRING_CONTENT = Regex(""""(?:[^"\\]|\\.)*"""")

    /** [line] with the contents of string literals blanked. */
    fun withoutStrings(line: String): String = line.replace(STRING_CONTENT, "\"\"")

    /** Calls (or definitions) that start a payment. */
    val PAYMENT_START = Regex(
        """\b(?:startPayment|takePayment|processPayment|submitPayment|makePayment|doPayment|startTransaction|startSale|authorize|charge|purchase)\s*\(""",
    )

    fun paymentScreens(project: ProjectView): List<PaymentScreen> = project.codeFiles.flatMap { file ->
        file.code.mapIndexedNotNull { index, line ->
            PAYMENT_SCREEN.find(withoutStrings(line))?.let { m ->
                PaymentScreen(file, index + 1, m.groupValues[1].ifEmpty { m.groupValues[2] })
            }
        }
    }

    /** `R.layout.<name>` references in [file]. */
    fun layoutsReferenced(file: SourceFile): Set<String> =
        file.code.flatMap { line -> Regex("""\bR\.layout\.(\w+)""").findAll(line).map { it.groupValues[1] } }.toSet()

    /** Layout XML files named [name] (any `layout*` resource directory). */
    fun layoutFiles(project: ProjectView, name: String): List<SourceFile> =
        project.files.filter { it.path.endsWith("/$name.xml") && it.path.substringBeforeLast('/').substringAfterLast('/').startsWith("layout") }

    // --- Identifier words ---------------------------------------------------

    private val CAMEL = Regex("""(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")
    private val TEMPLATE = Regex("""\$\{?([A-Za-z_]\w*)""")

    /** `cardNumber` → [card, number]; `PREF_LAST_TXN` → [pref, last, txn]. */
    fun words(identifier: String): List<String> =
        identifier.split('_', '-', '.', ' ').flatMap { it.split(CAMEL) }.filter { it.isNotEmpty() }.map { it.lowercase() }

    /**
     * Identifiers in [code], ignoring the text inside string literals but
     * keeping Kotlin string-template references (`"$pan"`).
     */
    fun identifiers(code: String): List<String> {
        val templates = STRING_LITERAL.findAll(code).flatMap { lit -> TEMPLATE.findAll(lit.value).map { it.groupValues[1] } }
        val withoutStrings = code.replace(STRING_LITERAL, " ")
        return IDENTIFIER.findAll(withoutStrings).map { it.value }.toList() + templates
    }

    private val CARD_WORDS = setOf("pan", "cvv", "cvv2", "cvc", "cvc2", "cvn", "track1", "track2", "pin", "pinblock")
    private val CARD_PAIRS = setOf(
        "card" to "number", "card" to "num", "card" to "no", "card" to "data",
        "track" to "data", "track" to "1", "track" to "2", "pin" to "block", "account" to "number",
    )

    /** True if [identifier] names card data or a PIN (`pan`, `cardNumber`, `track2`, `pinBlock`, ...). */
    fun isCardDataName(identifier: String): Boolean {
        val w = words(identifier)
        return w.any { it in CARD_WORDS } || w.zipWithNext().any { it in CARD_PAIRS }
    }

    private val PAYMENT_STATE_WORDS = setOf(
        "txn", "transaction", "transactions", "payment", "payments", "pending", "approval", "approved",
        "receipt", "amount", "tender", "charge", "sale", "settlement", "last4",
    )

    /** True if [name] looks like a key for payment state or card data. */
    fun isPaymentStateName(name: String): Boolean = words(name).any { it in PAYMENT_STATE_WORDS } || isCardDataName(name)
}
