package dev.posguard.assess.report

import dev.posguard.assess.Finding
import dev.posguard.assess.Report
import dev.posguard.assess.Risk

/** Turns a [Report] into text. */
public interface Reporter {
    public fun render(report: Report): String
}

private const val DISCLAIMER =
    "Heuristic scan by posguard-assess. Findings are leads to check, not proof: rules read source text, " +
        "so they can miss protections set elsewhere and flag code that is safe. No values from the scanned code are quoted."

private fun Enum<*>.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Findings grouped by rule, groups ordered by their best finding (the report is already ranked). */
private fun Report.byRule(): List<Pair<Finding, List<Finding>>> =
    findings.groupBy { it.ruleId }.values.map { it.first() to it }

private fun Report.summary(): String {
    val counts = countsByRisk()
    return "Files scanned: $filesScanned · Rules run: ${rulesRun.size} · Findings: ${findings.size} " +
        "(High ${counts[Risk.HIGH]}, Medium ${counts[Risk.MEDIUM]}, Low ${counts[Risk.LOW]})"
}

private fun Report.cleanRules(): List<String> = rulesRun.filter { id -> findings.none { it.ruleId == id } }

/** GitHub-flavored Markdown. */
public object MarkdownReporter : Reporter {

    private fun cell(text: String): String = text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")
    private fun code(text: String): String = "`" + text.replace("`", "'") + "`"

    override fun render(report: Report): String = buildString {
        appendLine("# posguard assessment: ${cell(report.rootName)}")
        appendLine()
        appendLine("> $DISCLAIMER")
        appendLine()
        appendLine(report.summary())
        appendLine()
        if (report.findings.isEmpty()) {
            appendLine("No findings.")
        } else {
            appendLine("## Fix first")
            appendLine()
            appendLine("Ranked by risk × ease of fix (9 = high risk, easy fix).")
            appendLine()
            appendLine("| # | Priority | Risk | Ease | MASVS | Finding | Location |")
            appendLine("|---|---|---|---|---|---|---|")
            report.findings.forEachIndexed { i, f ->
                appendLine(
                    "| ${i + 1} | ${f.priority} | ${f.risk.label()} | ${f.ease.label()} | ${f.masvs.label} | " +
                        "${cell(f.title)} | ${cell(code(f.location))} |",
                )
            }
            appendLine()
            appendLine("## Details")
            for ((first, group) in report.byRule()) {
                appendLine()
                appendLine("### ${first.title} (${code(first.ruleId)})")
                appendLine()
                val module = first.posguardModule?.let { " · posguard module: ${code(it)}" }.orEmpty()
                appendLine("Risk: ${first.risk.label()} · Ease of fix: ${first.ease.label()} · ${first.masvs.label}$module")
                appendLine()
                appendLine("**Why it matters.** ${first.whyItMatters}")
                appendLine()
                appendLine("**How to fix.** ${first.howToFix}")
                appendLine()
                group.forEach { appendLine("- ${code(it.location)}: ${it.detail}") }
            }
        }
        val clean = report.cleanRules()
        if (clean.isNotEmpty()) {
            appendLine()
            appendLine("## Rules with no findings")
            appendLine()
            clean.forEach { appendLine("- ${code(it)}") }
        }
    }
}

/** A self-contained HTML page: escaped text, inline CSS, no scripts. */
public object HtmlReporter : Reporter {

    internal fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private const val STYLE =
        "body{font-family:system-ui,sans-serif;max-width:1100px;margin:2em auto;padding:0 1em;color:#1b1b1b}" +
            "table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:.35em .5em;text-align:left;vertical-align:top}" +
            "th{background:#f3f3f3}code{background:#f3f3f3;padding:0 .2em}.note{border-left:4px solid #999;padding:.2em 1em;color:#444}" +
            ".HIGH{color:#a40000;font-weight:600}.MEDIUM{color:#8a5a00;font-weight:600}.LOW{color:#2d5f2d}"

    override fun render(report: Report): String = buildString {
        val e = ::escape
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\"><head><meta charset=\"utf-8\">")
        appendLine("<title>posguard assessment: ${e(report.rootName)}</title>")
        appendLine("<style>$STYLE</style></head><body>")
        appendLine("<h1>posguard assessment: ${e(report.rootName)}</h1>")
        appendLine("<p class=\"note\">${e(DISCLAIMER)}</p>")
        appendLine("<p>${e(report.summary())}</p>")
        if (report.findings.isEmpty()) {
            appendLine("<p>No findings.</p>")
        } else {
            appendLine("<h2>Fix first</h2>")
            appendLine("<p>Ranked by risk × ease of fix (9 = high risk, easy fix).</p>")
            appendLine("<table><thead><tr><th>#</th><th>Priority</th><th>Risk</th><th>Ease</th><th>MASVS</th><th>Finding</th><th>Location</th></tr></thead><tbody>")
            report.findings.forEachIndexed { i, f ->
                appendLine(
                    "<tr><td>${i + 1}</td><td>${f.priority}</td><td class=\"${f.risk.name}\">${f.risk.label()}</td>" +
                        "<td>${f.ease.label()}</td><td>${f.masvs.label}</td><td>${e(f.title)}</td><td><code>${e(f.location)}</code></td></tr>",
                )
            }
            appendLine("</tbody></table>")
            appendLine("<h2>Details</h2>")
            for ((first, group) in report.byRule()) {
                appendLine("<h3>${e(first.title)} (<code>${e(first.ruleId)}</code>)</h3>")
                val module = first.posguardModule?.let { " · posguard module: <code>${e(it)}</code>" }.orEmpty()
                appendLine(
                    "<p>Risk: <span class=\"${first.risk.name}\">${first.risk.label()}</span> · Ease of fix: ${first.ease.label()} · " +
                        "${first.masvs.label}$module</p>",
                )
                appendLine("<p><strong>Why it matters.</strong> ${e(first.whyItMatters)}</p>")
                appendLine("<p><strong>How to fix.</strong> ${e(first.howToFix)}</p>")
                appendLine("<ul>")
                group.forEach { appendLine("<li><code>${e(it.location)}</code>: ${e(it.detail)}</li>") }
                appendLine("</ul>")
            }
        }
        val clean = report.cleanRules()
        if (clean.isNotEmpty()) {
            appendLine("<h2>Rules with no findings</h2><ul>")
            clean.forEach { appendLine("<li><code>${e(it)}</code></li>") }
            appendLine("</ul>")
        }
        appendLine("</body></html>")
    }
}
