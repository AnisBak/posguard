plugins {
    alias(libs.plugins.posguard.jvm.library)
    alias(libs.plugins.posguard.publish)
}

description = "posguard public API: verdicts, reason codes, policy config, ledger contract."

dependencies {
    api(libs.kotlinx.coroutines.core)
}
