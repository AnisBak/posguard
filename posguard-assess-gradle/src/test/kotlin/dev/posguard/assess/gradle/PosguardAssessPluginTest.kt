package dev.posguard.assess.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Runs the plugin in a real Gradle build through TestKit. Groovy DSL keeps the test project free of Kotlin script compilation. */
class PosguardAssessPluginTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun project(extra: String = ""): File = temp.root.apply {
        resolve("settings.gradle").writeText("rootProject.name = 'legacy-shop'\n")
        resolve("build.gradle").writeText("plugins { id 'dev.posguard.assess' }\n$extra")
        resolve("app/src/main").mkdirs()
        resolve("app/src/main/AndroidManifest.xml").writeText("<application android:debuggable=\"true\" android:allowBackup=\"false\"/>")
        resolve("app/src/main/Prefs.kt").writeText("fun save() = prefs.edit().putString(\"pending_txn\", j)\n")
        resolve("app/src/test").mkdirs()
        resolve("app/src/test/PayTest.kt").writeText("fun t() = Log.d(TAG, pan)\n")
        resolve("app/build/generated").mkdirs()
        resolve("app/build/generated/Gen.kt").writeText("fun g() = Log.d(TAG, pan)\n")
    }

    private fun runner(dir: File, vararg args: String) =
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace")

    @Test
    fun `writes reports, skips tests and build output, and is up to date on rerun`() {
        val dir = project()
        val result = runner(dir, "assessPosguard").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":assessPosguard")?.outcome)
        assertTrue(result.output.contains("posguard-assess: 2 findings (high 1, medium 1, low 0) in 4 files.")) // 2 build scripts, manifest, Prefs.kt

        val md = dir.resolve("build/reports/posguard/assessment.md").readText()
        assertTrue(md.startsWith("# posguard assessment: legacy-shop"))
        assertTrue(md.contains("`app/src/main/AndroidManifest.xml:1`"))
        assertFalse(md.contains("PayTest.kt"))
        assertFalse(md.contains("Gen.kt"))
        assertTrue(dir.resolve("build/reports/posguard/assessment.html").readText().startsWith("<!DOCTYPE html>"))

        assertEquals(TaskOutcome.UP_TO_DATE, runner(dir, "assessPosguard").build().task(":assessPosguard")?.outcome)
    }

    @Test
    fun `failOn fails the build, and config can turn rules off`() {
        val dir = project("tasks.named('assessPosguard') { failOn = 'high' }\n")
        val failed = runner(dir, "assessPosguard").buildAndFail()
        assertTrue(failed.output.contains("posguard-assess found findings at or above high."))

        dir.resolve("assess.properties").writeText("posguard.assess.rule.manifest-debuggable.enabled=false\n")
        dir.resolve("build.gradle").appendText("tasks.named('assessPosguard') { configFile = file('assess.properties') }\n")
        assertEquals(TaskOutcome.SUCCESS, runner(dir, "assessPosguard").build().task(":assessPosguard")?.outcome)
    }

    @Test
    fun `bad config fails with a clear message`() {
        val dir = project("tasks.named('assessPosguard') { configFile = file('assess.properties') }\n")
        dir.resolve("assess.properties").writeText("posguard.assess.rule.nope.enabled=false\n")
        val failed = runner(dir, "assessPosguard").buildAndFail()
        assertTrue(failed.output.contains("Config names unknown rules: nope."))
    }
}
