import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Shared setup for pure Kotlin/JVM posguard modules (no Android framework),
 * such as `posguard-core`. These stay fast to test and usable from any
 * Android module.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")

        val targetVersion = libs.stringVersion("jvmTarget")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(targetVersion)
            targetCompatibility = JavaVersion.toVersion(targetVersion)
        }

        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(targetVersion))
            }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
