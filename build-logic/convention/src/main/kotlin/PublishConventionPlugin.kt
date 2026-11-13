import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType

/**
 * Maven publication setup. Publishing goes to a local build repository only;
 * Maven Central release is a Milestone 2 task and needs credentials that are
 * never committed.
 *
 * Apply after `posguard.android.library` or `posguard.jvm.library`.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("maven-publish")

        group = providers.gradleProperty("GROUP").get()
        version = providers.gradleProperty("VERSION_NAME").get()

        val android = extensions.findByType<LibraryExtension>()
        val componentName = if (android != null) {
            android.publishing {
                singleVariant("release") { withSourcesJar() }
            }
            "release"
        } else {
            extensions.configure<JavaPluginExtension> { withSourcesJar() }
            "java"
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    artifactId = project.name
                    afterEvaluate { from(components.getByName(componentName)) }
                    pom {
                        name.set(project.name)
                        description.set(provider { project.description ?: project.name })
                        // TODO: set `url` and `scm` once the public repository exists.
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "localBuild"
                    url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
                }
            }
        }
    }
}
