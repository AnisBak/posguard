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

// Milestone 1 modules. V2/V3 modules (devices, ebt, assess, ebt-simulator)
// are added when their milestone starts.
include(
    ":posguard-core",
    ":posguard-integrity",
    ":posguard-ledger",
    ":posguard-telemetry",
    ":mock-payment-engine",
    ":demo-app",
)
