// Optional Play Integrity integration. Kept out of posguard-integrity so apps on
// POS terminals without Google Play services do not pull in the Play libraries.
plugins {
    alias(libs.plugins.posguard.android.library)
    alias(libs.plugins.posguard.publish)
}

description = "Play Integrity verdict signals for posguard (requires Google Play services)."

android {
    namespace = "dev.posguard.integrity.play"
}

dependencies {
    api(project(":posguard-core"))
    implementation(libs.play.integrity)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
