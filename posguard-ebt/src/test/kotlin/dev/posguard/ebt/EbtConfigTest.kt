package dev.posguard.ebt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Properties

class EbtConfigTest {

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    @Test
    fun `loads categories, restrictions, jurisdiction, and order`() {
        val config = EbtConfig.fromProperties(
            props(
                "posguard.ebt.category.produce" to "eligible",
                "posguard.ebt.category.hot_food" to " INELIGIBLE ",
                "posguard.ebt.restricted" to "soda, candy",
                "posguard.ebt.jurisdiction" to "Example State",
                "posguard.ebt.entry.order" to "chip_tap,chip_insert",
                "other.key" to "ignored",
            ),
        )
        assertEquals(mapOf("produce" to Eligibility.ELIGIBLE, "hot_food" to Eligibility.INELIGIBLE), config.eligibility.categories)
        assertEquals(setOf("soda", "candy"), config.eligibility.restrictedCategories)
        assertEquals("Example State", config.eligibility.jurisdiction)
        assertEquals(listOf(EbtEntryMode.CHIP_TAP, EbtEntryMode.CHIP_INSERT), config.entryOrder)
    }

    @Test
    fun `defaults to chip insert, tap, then magstripe`() {
        val config = EbtConfig.fromProperties(props("posguard.ebt.category.produce" to "eligible"))
        assertEquals(EbtConfig.DEFAULT_ENTRY_ORDER, config.entryOrder)
        assertNull(config.eligibility.jurisdiction)
    }

    @Test
    fun `rejects unknown keys, bad values, and missing categories`() {
        val bad = listOf(
            props("posguard.ebt.category.produce" to "eligible", "posguard.ebt.typo" to "x"),
            props("posguard.ebt.category.produce" to "yes"),
            props("posguard.ebt.category.Produce" to "eligible"),
            props("posguard.ebt.jurisdiction" to "Example State"),
            props("posguard.ebt.category.produce" to "eligible", "posguard.ebt.entry.order" to "chip_insert,keyed"),
            props("posguard.ebt.category.produce" to "eligible", "posguard.ebt.restricted" to "soda,,candy"),
        )
        bad.forEach { p -> assertThrows(IllegalArgumentException::class.java) { EbtConfig.fromProperties(p) } }
    }

    @Test
    fun `magstripe must come after a chip mode and only last`() {
        val rules = EligibilityRules(mapOf("produce" to Eligibility.ELIGIBLE))
        val illegal = listOf(
            listOf(EbtEntryMode.MAGSTRIPE),
            listOf(EbtEntryMode.MAGSTRIPE, EbtEntryMode.CHIP_INSERT),
            listOf(EbtEntryMode.CHIP_INSERT, EbtEntryMode.MAGSTRIPE, EbtEntryMode.CHIP_TAP),
            listOf(EbtEntryMode.CHIP_INSERT, EbtEntryMode.CHIP_INSERT),
            emptyList(),
        )
        illegal.forEach { order -> assertThrows(IllegalArgumentException::class.java) { EbtConfig(rules, order) } }

        // Magstripe fallback off is fine.
        EbtConfig(rules, listOf(EbtEntryMode.CHIP_INSERT, EbtEntryMode.CHIP_TAP))
    }

    @Test
    fun `restriction beats eligibility and unknown categories are ineligible`() {
        val rules = EligibilityRules(
            categories = mapOf("produce" to Eligibility.ELIGIBLE, "soda" to Eligibility.ELIGIBLE, "hot_food" to Eligibility.INELIGIBLE),
            restrictedCategories = setOf("soda"),
        )
        assertEquals(LineReason.ELIGIBLE, rules.decide("produce"))
        assertEquals(LineReason.RESTRICTED_IN_JURISDICTION, rules.decide("soda"))
        assertEquals(LineReason.INELIGIBLE_CATEGORY, rules.decide("hot_food"))
        assertEquals(LineReason.UNKNOWN_CATEGORY, rules.decide("mystery"))
    }
}
