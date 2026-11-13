plugins {
    alias(libs.plugins.posguard.android.library)
    alias(libs.plugins.posguard.publish)
}

description = "Outage-safe, idempotent transaction journal and recovery for posguard."

android {
    namespace = "dev.posguard.ledger"
}

dependencies {
    api(project(":posguard-core"))
}
