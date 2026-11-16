package dev.posguard.assess.cli

import dev.posguard.assess.AssessConfig
import dev.posguard.assess.Assessor
import dev.posguard.assess.Risk
import dev.posguard.assess.RuleRegistry
import dev.posguard.assess.report.HtmlReporter
import dev.posguard.assess.report.MarkdownReporter
import java.io.File
import java.io.PrintStream
import java.util.Properties
import kotlin.system.exitProcess

/** Command-line entry point. See [Cli.USAGE]. */
public fun main(args: Array<String>) {
    exitProcess(Cli.run(args, System.out, System.err))
}

/** The `posguard-assess` command, separated from [main] so it can be tested without exiting. */
public object Cli {
    public const val EXIT_OK: Int = 0
    public const val EXIT_FINDINGS: Int = 1
    public const val EXIT_USAGE: Int = 2

    public val USAGE: String = """
        Usage: posguard-assess <path> [options]

        Scans an Android POS codebase and reports prioritized, MASVS-mapped gaps.

        Options:
          --out <file>        Write the Markdown report to <file> (default: print it)
          --html <file>       Also write an HTML report to <file>
          --config <file>     Properties file with posguard.assess.* settings
          --fail-on <risk>    Exit with 1 if any finding is at or above high, medium, or low
          --list-rules        List the rules and exit
          -h, --help          Show this help

        Exit codes: 0 done, 1 findings at or above --fail-on, 2 usage or config error.
    """.trimIndent()

    private data class Options(
        val path: String? = null,
        val out: String? = null,
        val html: String? = null,
        val config: String? = null,
        val failOn: Risk? = null,
        val listRules: Boolean = false,
        val help: Boolean = false,
    )

    /** Runs the command and returns its exit code. */
    public fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val options = try {
            parse(args)
        } catch (e: IllegalArgumentException) {
            err.println("posguard-assess: ${e.message}")
            err.println(USAGE)
            return EXIT_USAGE
        }
        if (options.help) {
            out.println(USAGE)
            return EXIT_OK
        }
        if (options.listRules) {
            RuleRegistry.discover().forEach {
                out.println("${it.id}  [${it.defaultRisk.name.lowercase()}/${it.defaultEase.name.lowercase()}, ${it.masvs.label}]  ${it.title}")
            }
            return EXIT_OK
        }

        return try {
            val root = File(requireNotNull(options.path) { "missing <path>." })
            require(root.isDirectory) { "not a directory: ${options.path}" }
            val config = options.config?.let { loadConfig(File(it)) } ?: AssessConfig()
            val report = Assessor(RuleRegistry.discover(), config).run(root)

            val markdown = MarkdownReporter.render(report)
            if (options.out != null) {
                write(File(options.out), markdown)
            } else {
                out.print(markdown)
            }
            options.html?.let { write(File(it), HtmlReporter.render(report)) }

            val counts = report.countsByRisk()
            val summary = "${report.findings.size} findings (high ${counts[Risk.HIGH]}, medium ${counts[Risk.MEDIUM]}, " +
                "low ${counts[Risk.LOW]}) in ${report.filesScanned} files."
            // Keep stdout clean for the report when it is printed there.
            (if (options.out != null) out else err).println("posguard-assess: $summary")

            if (options.failOn != null && report.hasFindingsAtOrAbove(options.failOn)) EXIT_FINDINGS else EXIT_OK
        } catch (e: IllegalArgumentException) {
            err.println("posguard-assess: ${e.message}")
            EXIT_USAGE
        }
    }

    private fun parse(args: Array<String>): Options {
        var options = Options()
        var i = 0
        fun value(flag: String): String {
            require(i + 1 < args.size && !args[i + 1].startsWith("--")) { "$flag needs a value." }
            return args[++i]
        }
        while (i < args.size) {
            when (val arg = args[i]) {
                "-h", "--help" -> options = options.copy(help = true)
                "--list-rules" -> options = options.copy(listRules = true)
                "--out" -> options = options.copy(out = value(arg))
                "--html" -> options = options.copy(html = value(arg))
                "--config" -> options = options.copy(config = value(arg))
                "--fail-on" -> {
                    val raw = value(arg)
                    val risk = Risk.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: throw IllegalArgumentException("--fail-on must be high, medium, or low.")
                    options = options.copy(failOn = risk)
                }
                else -> {
                    require(!arg.startsWith("-")) { "unknown option $arg." }
                    require(options.path == null) { "only one <path> is allowed." }
                    options = options.copy(path = arg)
                }
            }
            i++
        }
        require(options.help || options.listRules || options.path != null) { "missing <path>." }
        return options
    }

    private fun loadConfig(file: File): AssessConfig {
        require(file.isFile) { "config file not found: ${file.path}" }
        val properties = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        return AssessConfig.fromProperties(properties)
    }

    private fun write(file: File, text: String) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
}
