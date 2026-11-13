plugins {
    `kotlin-dsl`
}

group = "dev.posguard.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = libs.plugins.posguard.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.posguard.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("publish") {
            id = libs.plugins.posguard.publish.get().pluginId
            implementationClass = "PublishConventionPlugin"
        }
    }
}
