package dev.posguard.assess.report

import dev.posguard.assess.Ease
import dev.posguard.assess.Finding
import dev.posguard.assess.MasvsCategory
import dev.posguard.assess.Report
import dev.posguard.assess.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportersTest {

    private fun finding(rule: String, risk: Risk, ease: Ease, file: String?, line: Int?, title: String = "Title $rule") = Finding(
        ruleId = rule, title = title, masvs = MasvsCategory.PLATFORM, risk = risk, ease = ease, file = file, line = line,
        detail = "detail for $rule", whyItMatters = "why $rule", howToFix = "fix $rule", posguardModule = "posguard-integrity",
    )

    private val report = Report(
        rootName = "legacy-pos",
        filesScanned = 4,
        rulesRun = listOf("a-rule", "b-rule", "clean-rule"),
        findings = listOf(
            finding("a-rule", Risk.HIGH, Ease.EASY, "app/Pay.kt", 3, title = "Pipe | and <script>"),
            finding("a-rule", Risk.HIGH, Ease.EASY, "app/Other.kt", 9, title = "Pipe | and <script>"),
            finding("b-rule", Risk.LOW, Ease.HARD, null, null),
        ),
    )

    @Test
    fun `markdown has summary, ranked table, grouped details, and clean rules`() {
        val md = MarkdownReporter.render(report)
        assertTrue(md.startsWith("# posguard assessment: legacy-pos\n"))
        assertTrue(md.contains("Files scanned: 4 · Rules run: 3 · Findings: 3 (High 2, Medium 0, Low 1)"))
        assertTrue(md.contains("| 1 | 9 | High | Easy | MASVS-PLATFORM | Pipe \\| and <script> | `app/Pay.kt:3` |"))
        assertTrue(md.contains("| 3 | 1 | Low | Hard | MASVS-PLATFORM | Title b-rule | `(project)` |"))
        assertTrue(md.contains("### Pipe | and <script> (`a-rule`)"))
        assertTrue(md.contains("- `app/Other.kt:9`: detail for a-rule"))
        assertTrue(md.contains("posguard module: `posguard-integrity`"))
        assertTrue(md.contains("## Rules with no findings\n\n- `clean-rule`"))
        assertEquals(1, Regex("### .*a-rule").findAll(md).count())
    }

    @Test
    fun `html escapes everything from findings and has no scripts`() {
        val html = HtmlReporter.render(report)
        assertTrue(html.contains("Pipe | and &lt;script&gt;"))
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("<td class=\"HIGH\">High</td>"))
        assertTrue(html.contains("<li><code>clean-rule</code></li>"))
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlReporter.escape("&<>\"'"))
    }

    @Test
    fun `empty report says no findings`() {
        val empty = Report("ok-app", 2, listOf("a-rule"), emptyList())
        assertTrue(MarkdownReporter.render(empty).contains("No findings."))
        assertTrue(HtmlReporter.render(empty).contains("<p>No findings.</p>"))
    }
}
