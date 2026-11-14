plugins {
    alias(libs.plugins.posguard.android.library)
    alias(libs.plugins.posguard.publish)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

description = "Outage-safe, idempotent transaction journal and recovery for posguard."

android {
    namespace = "dev.posguard.ledger"
}

room {
    // Exported schemas are committed so future migrations can be tested.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":posguard-core"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.work.testing)
}
