// Gradle plugin `dev.posguard.assess`: adds the `assessPosguard` task.
// Uses Gradle's built-in plugin tooling (gradleApi, TestKit); no new dependencies.
// Not published yet: Maven Central / Plugin Portal publishing is a later Milestone 4 task.
plugins {
    alias(libs.plugins.posguard.jvm.library)
    `java-gradle-plugin`
}

description = "Gradle plugin that runs posguard-assess on a project and writes Markdown and HTML reports."

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

gradlePlugin {
    plugins {
        register("posguardAssess") {
            id = "dev.posguard.assess"
            implementationClass = "dev.posguard.assess.gradle.PosguardAssessPlugin"
            displayName = "posguard assess"
            description = project.description
        }
    }
}

dependencies {
    implementation(project(":posguard-assess"))
}
