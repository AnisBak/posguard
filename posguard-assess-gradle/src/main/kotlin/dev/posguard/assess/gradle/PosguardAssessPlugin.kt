package dev.posguard.assess.gradle

import dev.posguard.assess.AssessConfig
import dev.posguard.assess.Assessor
import dev.posguard.assess.Risk
import dev.posguard.assess.RuleRegistry
import dev.posguard.assess.SourceTree
import dev.posguard.assess.report.HtmlReporter
import dev.posguard.assess.report.MarkdownReporter
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

/**
 * Adds `assessPosguard`, which scans the project directory (all modules under
 * it) and writes `build/reports/posguard/assessment.md` and `.html`.
 *
 * ```groovy
 * plugins { id 'dev.posguard.assess' }
 * tasks.named('assessPosguard') {
 *     configFile = file('posguard-assess.properties')   // optional
 *     failOn = 'high'                                     // optional: fail the build
 * }
 * ```
 */
public class PosguardAssessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register(TASK_NAME, AssessTask::class.java) { task ->
            task.group = "verification"
            task.description = "Scans this project for payment-app security gaps (posguard-assess)."
            task.rootDirectory.set(project.layout.projectDirectory)
            task.rootName.set(project.name)
            task.sources.from(
                project.fileTree(project.projectDir) { tree ->
                    tree.include("**/*.kt", "**/*.kts", "**/*.java", "**/*.xml", "**/*.gradle", "**/*.toml")
                    SourceTree.SKIPPED_DIRECTORIES.forEach { tree.exclude("**/$it/**") }
                },
            )
            task.markdownReport.set(project.layout.buildDirectory.file("reports/posguard/assessment.md"))
            task.htmlReport.set(project.layout.buildDirectory.file("reports/posguard/assessment.html"))
        }
    }

    public companion object {
        public const val TASK_NAME: String = "assessPosguard"
    }
}

/** Runs posguard-assess over [sources]. Cacheable: same inputs, same report. */
@CacheableTask
public abstract class AssessTask : DefaultTask() {
    /** Paths in the report are relative to this directory. */
    @get:Internal
    public abstract val rootDirectory: DirectoryProperty

    /** Shown as the report title. */
    @get:Input
    public abstract val rootName: Property<String>

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val sources: ConfigurableFileCollection

    /** Optional properties file with `posguard.assess.*` settings. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val configFile: RegularFileProperty

    /** `high`, `medium`, or `low`: fail the task if any finding is at or above it. Unset: never fail. */
    @get:Input
    @get:Optional
    public abstract val failOn: Property<String>

    @get:OutputFile
    public abstract val markdownReport: RegularFileProperty

    @get:OutputFile
    @get:Optional
    public abstract val htmlReport: RegularFileProperty

    @TaskAction
    public fun assess() {
        val threshold = failOn.orNull?.let { raw ->
            Risk.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw GradleException("failOn must be high, medium, or low (was '$raw').")
        }
        val config = try {
            configFile.orNull?.asFile?.let { file ->
                AssessConfig.fromProperties(Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } })
            } ?: AssessConfig()
        } catch (e: IllegalArgumentException) {
            throw GradleException("posguard-assess config: ${e.message}", e)
        }

        val root = rootDirectory.get().asFile
        val skipped = if (config.includeTests) emptySet() else SourceTree.TEST_DIRECTORIES
        val files = SourceTree.read(root, sources.files).filter { file ->
            file.path.split('/').dropLast(1).none { it in skipped }
        }
        val report = try {
            Assessor(RuleRegistry.discover(AssessTask::class.java.classLoader), config).assess(rootName.get(), files)
        } catch (e: IllegalArgumentException) {
            throw GradleException("posguard-assess: ${e.message}", e)
        }

        markdownReport.get().asFile.apply { parentFile.mkdirs() }.writeText(MarkdownReporter.render(report), Charsets.UTF_8)
        htmlReport.orNull?.asFile?.apply { parentFile.mkdirs() }?.writeText(HtmlReporter.render(report), Charsets.UTF_8)

        val counts = report.countsByRisk()
        val summary = "${report.findings.size} findings (high ${counts[Risk.HIGH]}, medium ${counts[Risk.MEDIUM]}, " +
            "low ${counts[Risk.LOW]}) in ${report.filesScanned} files. Report: ${markdownReport.get().asFile}"
        logger.lifecycle("posguard-assess: $summary")
        if (threshold != null && report.hasFindingsAtOrAbove(threshold)) {
            throw GradleException("posguard-assess found findings at or above ${threshold.name.lowercase()}. $summary")
        }
    }
}
