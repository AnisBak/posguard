import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Shared setup for posguard Android library modules.
 *
 * AGP 9 compiles Kotlin itself (built-in Kotlin), so the
 * `org.jetbrains.kotlin.android` plugin is intentionally not applied.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        val javaVersion = JavaVersion.toVersion(libs.stringVersion("jvmTarget"))

        extensions.configure<LibraryExtension> {
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            // Public library APIs must declare visibility and return types.
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(libs.stringVersion("jvmTarget")))
            }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
