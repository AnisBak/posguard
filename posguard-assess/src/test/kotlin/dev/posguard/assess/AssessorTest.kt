package dev.posguard.assess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class AssessorTest {

    private class FakeRule(
        override val id: String,
        override val defaultRisk: Risk = Risk.MEDIUM,
        override val defaultEase: Ease = Ease.MODERATE,
        override val options: Map<String, String> = emptyMap(),
        private val hits: (RuleContext) -> List<RuleHit> = { listOf(RuleHit("A.kt", 1, "hit")) },
    ) : AssessRule {
        override val title = "Fake $id"
        override val masvs = MasvsCategory.CODE
        override val whyItMatters = "why"
        override val howToFix = "fix"
        override fun check(context: RuleContext) = hits(context)
    }

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    @Test
    fun `ranks by risk times ease, then risk, rule id, and location`() {
        val rules = listOf(
            FakeRule("high-hard", Risk.HIGH, Ease.HARD),                 // 3
            FakeRule("low-easy", Risk.LOW, Ease.EASY),                   // 3, lower risk
            FakeRule("high-easy", Risk.HIGH, Ease.EASY) {                // 9
                listOf(RuleHit("B.kt", 9, "b"), RuleHit("A.kt", 20, "a"), RuleHit("A.kt", 3, "a"))
            },
            FakeRule("medium-moderate"),                                 // 4
        )
        val report = Assessor(rules).assess("root", emptyList())
        assertEquals(
            listOf("high-easy@A.kt:3", "high-easy@A.kt:20", "high-easy@B.kt:9", "medium-moderate@A.kt:1", "high-hard@A.kt:1", "low-easy@A.kt:1"),
            report.findings.map { "${it.ruleId}@${it.location}" },
        )
        assertEquals(9, report.findings.first().priority)
        assertEquals(mapOf(Risk.HIGH to 4, Risk.MEDIUM to 1, Risk.LOW to 1), report.countsByRisk())
        assertTrue(report.hasFindingsAtOrAbove(Risk.HIGH))
    }

    @Test
    fun `config disables rules and overrides risk and ease`() {
        val config = AssessConfig.fromProperties(
            props(
                "posguard.assess.rule.a.enabled" to "false",
                "posguard.assess.rule.b.risk" to "LOW",
                "posguard.assess.rule.b.ease" to "hard",
            ),
        )
        val report = Assessor(listOf(FakeRule("a"), FakeRule("b", Risk.HIGH, Ease.EASY)), config).assess("root", emptyList())
        assertEquals(listOf("b"), report.rulesRun)
        assertEquals(Risk.LOW, report.findings.single().risk)
        assertEquals(Ease.HARD, report.findings.single().ease)
        assertFalse(report.hasFindingsAtOrAbove(Risk.MEDIUM))
    }

    @Test
    fun `rule options reach the rule`() {
        var seen: Map<String, String>? = null
        val rule = FakeRule("a", options = mapOf("limit" to "a limit")) { seen = it.options; emptyList() }
        Assessor(listOf(rule), AssessConfig.fromProperties(props("posguard.assess.rule.a.limit" to " 5 "))).assess("r", emptyList())
        assertEquals(mapOf("limit" to "5"), seen)
    }

    @Test
    fun `duplicate hits are reported once`() {
        val rule = FakeRule("a") { listOf(RuleHit("A.kt", 1, "x"), RuleHit("A.kt", 1, "x")) }
        assertEquals(1, Assessor(listOf(rule)).assess("r", emptyList()).findings.size)
    }

    @Test
    fun `rejects unknown rules, unknown options, and duplicate ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            Assessor(listOf(FakeRule("a")), AssessConfig(disabledRules = setOf("nope")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Assessor(listOf(FakeRule("a")), AssessConfig(ruleOptions = mapOf("a" to mapOf("limit" to "1"))))
        }
        assertThrows(IllegalArgumentException::class.java) { Assessor(listOf(FakeRule("a"), FakeRule("a"))) }
    }

    @Test
    fun `config parsing rejects bad keys and values`() {
        val bad = listOf(
            "posguard.assess.unknown" to "x",
            "posguard.assess.rule.a" to "x",
            "posguard.assess.rule.Bad_Id.enabled" to "false",
            "posguard.assess.rule.a.enabled" to "maybe",
            "posguard.assess.rule.a.risk" to "critical",
            "posguard.assess.include.tests" to "yes",
        )
        for (pair in bad) {
            assertThrows(pair.first, IllegalArgumentException::class.java) { AssessConfig.fromProperties(props(pair)) }
        }
        val ok = AssessConfig.fromProperties(props("posguard.assess.include.tests" to "true", "other.key" to "ignored"))
        assertTrue(ok.includeTests)
    }

    @Test
    fun `discover returns built-in rules with unique ids`() {
        val rules = RuleRegistry.discover()
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
    }
}
