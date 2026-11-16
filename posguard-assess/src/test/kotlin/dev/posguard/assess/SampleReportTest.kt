package dev.posguard.assess

import dev.posguard.assess.report.MarkdownReporter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * End to end over the fictional `samples/legacy-pos-sample`: every built-in rule
 * fires, and the output matches the committed example report. To refresh it:
 * `./gradlew :posguard-assess:run --args="samples/legacy-pos-sample --out docs/examples/assess-legacy-pos-sample.md"`.
 */
class SampleReportTest {

    private val repo = File(System.getProperty("posguard.repoRoot") ?: "..")
    private val sample = repo.resolve("samples/legacy-pos-sample")
    private val committed = repo.resolve("docs/examples/assess-legacy-pos-sample.md")

    @Test
    fun `every built-in rule fires on the sample`() {
        val report = Assessor().run(sample)
        val fired = report.findings.map { it.ruleId }.toSet()
        assertEquals(RuleRegistry.builtIn().map { it.id }.toSet(), fired)
    }

    @Test
    fun `report matches the committed example`() {
        val actual = MarkdownReporter.render(Assessor().run(sample))
        assertEquals(committed.readText().replace("\r\n", "\n"), actual)
    }
}
