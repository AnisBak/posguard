// NOT A REAL EBT PROCESSOR. A fake stand-in for a certified, EBT-capable
// payment engine, used only by tests and the demo app. It never touches card
// data, PINs, or real benefit accounts.
plugins {
    alias(libs.plugins.posguard.android.library)
}

android {
    namespace = "dev.posguard.ebtsim"
}

dependencies {
    api(project(":posguard-ebt"))

    testImplementation(project(":posguard-ledger")) // end-to-end checkout tests only
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
