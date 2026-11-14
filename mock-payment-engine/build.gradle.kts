// NOT A REAL PAYMENT ENGINE. A fake stand-in for a certified SoftPOS SDK,
// used only by tests and the demo app. It never touches card data.
plugins {
    alias(libs.plugins.posguard.android.library)
}

android {
    namespace = "dev.posguard.mockengine"
}

dependencies {
    api(project(":posguard-core"))

    testImplementation(project(":posguard-ledger")) // end-to-end scenario tests only
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
