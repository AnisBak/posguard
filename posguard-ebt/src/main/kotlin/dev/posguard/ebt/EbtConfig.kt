package dev.posguard.ebt

import java.util.Properties

/** Whether an item category can be paid with SNAP benefits. */
public enum class Eligibility {
    ELIGIBLE,
    INELIGIBLE,
}

/**
 * How the card is presented to the certified engine. posguard never reads the
 * card. It only decides which mode to prompt next.
 */
public enum class EbtEntryMode {
    /** Chip card inserted into the reader. */
    CHIP_INSERT,

    /** Chip card tapped (NFC, contactless). */
    CHIP_TAP,

    /** Magnetic stripe swiped. Fallback only. */
    MAGSTRIPE,
    ;

    /** True for [CHIP_INSERT] and [CHIP_TAP]. */
    public val isChip: Boolean get() = this != MAGSTRIPE
}

/**
 * SNAP item-eligibility rules, supplied by the integrating app from config
 * (CLAUDE.md §12: never hardcoded in the library). posguard ships **no**
 * eligibility list. Federal rules, state restrictions (for example, waivers
 * that restrict some categories), and the retailer's own item catalog decide
 * what goes here.
 *
 * Categories not in [categories] are treated as **ineligible**, with reason
 * [LineReason.UNKNOWN_CATEGORY], so a catalog gap never lets an item onto the
 * SNAP leg by accident.
 */
public data class EligibilityRules(
    /** Category id → eligibility. Ids are lowercase letters, digits, and `_`. */
    val categories: Map<String, Eligibility>,
    /** Categories restricted in this jurisdiction. Always ineligible, whatever [categories] says. */
    val restrictedCategories: Set<String> = emptySet(),
    /** Label for the jurisdiction these rules describe, shown in explanations. Not used in decisions. */
    val jurisdiction: String? = null,
) {
    init {
        (categories.keys + restrictedCategories).forEach {
            require(CATEGORY_ID.matches(it)) { "Category id '$it' must match ${CATEGORY_ID.pattern}." }
        }
    }

    /** The decision for one category. */
    public fun decide(category: String): LineReason = when {
        category in restrictedCategories -> LineReason.RESTRICTED_IN_JURISDICTION
        else -> when (categories[category]) {
            Eligibility.ELIGIBLE -> LineReason.ELIGIBLE
            Eligibility.INELIGIBLE -> LineReason.INELIGIBLE_CATEGORY
            null -> LineReason.UNKNOWN_CATEGORY
        }
    }

    internal companion object {
        val CATEGORY_ID = Regex("[a-z0-9_]{1,64}")
    }
}

/**
 * EBT settings loaded from outside the library.
 *
 * [entryOrder] is the order card-entry modes are prompted in. Chip modes must
 * come first and [EbtEntryMode.MAGSTRIPE], if present, last: USDA's proposed
 * SNAP EBT operating standards (91 FR 66958, Appendix A (p)(4)) say a magnetic
 * stripe transaction must not be permitted "until there has been at least one
 * failed attempt to conduct the transaction via electronic chip". Leave
 * MAGSTRIPE out to turn magstripe fallback off. The standards don't set an
 * order between insert and tap, so that is the app's choice.
 */
public data class EbtConfig(
    val eligibility: EligibilityRules,
    val entryOrder: List<EbtEntryMode> = DEFAULT_ENTRY_ORDER,
) {
    init {
        require(entryOrder.isNotEmpty()) { "entryOrder must not be empty." }
        require(entryOrder.toSet().size == entryOrder.size) { "entryOrder must not repeat a mode." }
        require(entryOrder.first().isChip) { "entryOrder must start with a chip mode (insert or tap)." }
        require(EbtEntryMode.MAGSTRIPE !in entryOrder || entryOrder.last() == EbtEntryMode.MAGSTRIPE) {
            "MAGSTRIPE may only be the last entry mode (fallback after a failed chip attempt)."
        }
    }

    public companion object {
        public val DEFAULT_ENTRY_ORDER: List<EbtEntryMode> =
            listOf(EbtEntryMode.CHIP_INSERT, EbtEntryMode.CHIP_TAP, EbtEntryMode.MAGSTRIPE)

        /** Prefix for keys read by [fromProperties]. */
        public const val PROPERTY_PREFIX: String = "posguard.ebt."

        private const val CATEGORY_PREFIX = "category."
        private const val RESTRICTED = "restricted"
        private const val JURISDICTION = "jurisdiction"
        private const val ENTRY_ORDER = "entry.order"

        /**
         * Reads EBT config with the `posguard.ebt.` prefix:
         *
         * | Key | Value |
         * |---|---|
         * | `category.<id>` | `eligible` or `ineligible` (at least one is required) |
         * | `restricted` | comma-separated category ids restricted in this jurisdiction |
         * | `jurisdiction` | free-text label |
         * | `entry.order` | comma-separated `chip_insert`, `chip_tap`, `magstripe` |
         *
         * Keys without the prefix are ignored, so one file can hold several configs.
         *
         * @throws IllegalArgumentException on unknown keys or malformed values.
         */
        public fun fromProperties(properties: Properties): EbtConfig {
            val values = properties.stringPropertyNames()
                .filter { it.startsWith(PROPERTY_PREFIX) }
                .associate { it.removePrefix(PROPERTY_PREFIX) to properties.getProperty(it).trim() }

            val categories = mutableMapOf<String, Eligibility>()
            for ((key, value) in values) {
                when {
                    key.startsWith(CATEGORY_PREFIX) -> {
                        val id = key.removePrefix(CATEGORY_PREFIX)
                        categories[id] = when (value.lowercase()) {
                            "eligible" -> Eligibility.ELIGIBLE
                            "ineligible" -> Eligibility.INELIGIBLE
                            else -> throw IllegalArgumentException(
                                "$PROPERTY_PREFIX$key must be 'eligible' or 'ineligible'.",
                            )
                        }
                    }
                    key == RESTRICTED || key == JURISDICTION || key == ENTRY_ORDER -> Unit
                    else -> throw IllegalArgumentException("Unknown EBT key '$PROPERTY_PREFIX$key'.")
                }
            }
            require(categories.isNotEmpty()) {
                "EBT config needs at least one ${PROPERTY_PREFIX}${CATEGORY_PREFIX}<id> entry."
            }

            fun list(key: String): List<String>? = values[key]?.split(',')?.map { it.trim() }?.also { items ->
                require(items.none { it.isEmpty() }) { "$PROPERTY_PREFIX$key has an empty item." }
            }

            val order = list(ENTRY_ORDER)?.map { name ->
                EbtEntryMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: throw IllegalArgumentException("Unknown entry mode '$name' in $PROPERTY_PREFIX$ENTRY_ORDER.")
            }

            return EbtConfig(
                eligibility = EligibilityRules(
                    categories = categories,
                    restrictedCategories = list(RESTRICTED)?.toSet().orEmpty(),
                    jurisdiction = values[JURISDICTION]?.ifEmpty { null },
                ),
                entryOrder = order ?: DEFAULT_ENTRY_ORDER,
            )
        }
    }
}
