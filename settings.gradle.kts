pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "posguard"

// Modules not yet started (devices, assess)
// are added when their milestone starts.
include(
    ":posguard-core",
    ":posguard-integrity",
    ":posguard-integrity-play",
    ":posguard-ledger",
    ":posguard-telemetry",
    ":posguard-ebt",
    ":mock-payment-engine",
    ":demo-app",
)
