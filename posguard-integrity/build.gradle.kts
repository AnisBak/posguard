plugins {
    alias(libs.plugins.posguard.android.library)
    alias(libs.plugins.posguard.publish)
}

description = "Device, app, and screen integrity signals for posguard."

android {
    namespace = "dev.posguard.integrity"
}

dependencies {
    api(project(":posguard-core"))
}
