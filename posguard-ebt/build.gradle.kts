plugins {
    alias(libs.plugins.posguard.android.library)
    alias(libs.plugins.posguard.publish)
}

description = "EBT (SNAP and cash benefits) checkout flows for posguard: config-driven item eligibility, " +
    "split tender, chip-first card entry with fallback, partial approvals, receipt fields. " +
    "Wraps a certified engine; never handles card data or PINs."

android {
    namespace = "dev.posguard.ebt"
}

dependencies {
    api(project(":posguard-core"))

    testImplementation(project(":posguard-ledger"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
