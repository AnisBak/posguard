// Pure JVM: scans a codebase as text. No Android, no new dependencies.
plugins {
    alias(libs.plugins.posguard.jvm.library)
    alias(libs.plugins.posguard.publish)
    application
}

description = "posguard assessment: scans an Android POS codebase and reports prioritized, MASVS-mapped gaps."

application {
    applicationName = "posguard-assess"
    mainClass.set("dev.posguard.assess.cli.CliKt")
}

// `./gradlew :posguard-assess:run --args="<path>"` resolves paths from the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// SampleReportTest scans the fictional sample app and compares with the committed example report.
tasks.named<Test>("test") {
    val repoRoot = rootProject.projectDir
    systemProperty("posguard.repoRoot", repoRoot.absolutePath)
    inputs.dir(repoRoot.resolve("samples/legacy-pos-sample")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(repoRoot.resolve("docs/examples/assess-legacy-pos-sample.md")).withPathSensitivity(PathSensitivity.RELATIVE)
}
