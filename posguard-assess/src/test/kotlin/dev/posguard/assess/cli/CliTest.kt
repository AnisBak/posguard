package dev.posguard.assess.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CliTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Result(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun insecureApp() = temp.newFolder("shop").also { root ->
        root.resolve("app/src/main").mkdirs()
        root.resolve("app/src/main/AndroidManifest.xml").writeText("<application android:debuggable=\"true\" android:allowBackup=\"false\">")
        root.resolve("app/src/main/Prefs.kt").writeText("prefs.edit().putString(\"pending_txn\", j)")
    }

    @Test
    fun `prints the markdown report and a summary on stderr`() {
        val root = insecureApp()
        val r = run(root.path)
        assertEquals(Cli.EXIT_OK, r.code)
        assertTrue(r.out.startsWith("# posguard assessment: shop"))
        assertTrue(r.out.contains("manifest-debuggable"))
        assertTrue(r.err.contains("2 findings (high 1, medium 1, low 0) in 2 files."))
    }

    @Test
    fun `writes markdown and html files`() {
        val root = insecureApp()
        val md = temp.root.resolve("out/report.md")
        val html = temp.root.resolve("out/report.html")
        val r = run(root.path, "--out", md.path, "--html", html.path)
        assertEquals(Cli.EXIT_OK, r.code)
        assertTrue(md.readText().contains("## Fix first"))
        assertTrue(html.readText().startsWith("<!DOCTYPE html>"))
        assertTrue(r.out.contains("posguard-assess: 2 findings"))
    }

    @Test
    fun `fail-on sets the exit code`() {
        val root = insecureApp()
        assertEquals(Cli.EXIT_FINDINGS, run(root.path, "--fail-on", "high").code)
        val config = temp.newFile("assess.properties").apply {
            writeText("posguard.assess.rule.manifest-debuggable.enabled=false\n")
        }
        assertEquals(Cli.EXIT_OK, run(root.path, "--fail-on", "HIGH", "--config", config.path).code)
        assertEquals(Cli.EXIT_FINDINGS, run(root.path, "--fail-on", "medium", "--config", config.path).code)
    }

    @Test
    fun `usage and config errors exit with 2`() {
        val root = insecureApp()
        assertEquals(Cli.EXIT_USAGE, run().code)
        assertEquals(Cli.EXIT_USAGE, run("--bogus", root.path).code)
        assertEquals(Cli.EXIT_USAGE, run(root.path, "--fail-on", "critical").code)
        assertEquals(Cli.EXIT_USAGE, run(root.path, "--out").code)
        assertEquals(Cli.EXIT_USAGE, run(root.path, root.path).code)
        assertEquals(Cli.EXIT_USAGE, run(temp.root.resolve("missing").path).code)
        assertEquals(Cli.EXIT_USAGE, run(root.path, "--config", temp.root.resolve("none.properties").path).code)
        val badConfig = temp.newFile("bad.properties").apply { writeText("posguard.assess.rule.no-such-rule.enabled=false\n") }
        val r = run(root.path, "--config", badConfig.path)
        assertEquals(Cli.EXIT_USAGE, r.code)
        assertTrue(r.err.contains("unknown rules: no-such-rule"))
    }

    @Test
    fun `help and list-rules`() {
        val help = run("--help")
        assertEquals(Cli.EXIT_OK, help.code)
        assertTrue(help.out.startsWith("Usage: posguard-assess"))
        val list = run("--list-rules")
        assertEquals(Cli.EXIT_OK, list.code)
        assertTrue(list.out.contains("card-data-in-logs  [high/easy, MASVS-PRIVACY]"))
    }
}
