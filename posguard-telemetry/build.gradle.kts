plugins {
    alias(libs.plugins.posguard.android.library)
    alias(libs.plugins.posguard.publish)
}

description = "Secure event reporting with card-data scrubbing and pluggable sinks for posguard."

android {
    namespace = "dev.posguard.telemetry"
}

dependencies {
    api(project(":posguard-core"))
}
