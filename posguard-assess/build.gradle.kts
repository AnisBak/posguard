// Pure JVM: scans a codebase as text. No Android, no new dependencies.
plugins {
    alias(libs.plugins.posguard.jvm.library)
    alias(libs.plugins.posguard.publish)
    application
}

description = "posguard assessment: scans an Android POS codebase and reports prioritized, MASVS-mapped gaps."

application {
    applicationName = "posguard-assess"
    mainClass.set("dev.posguard.assess.cli.MainKt")
}

// `./gradlew :posguard-assess:run --args="<path>"` resolves paths from the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
