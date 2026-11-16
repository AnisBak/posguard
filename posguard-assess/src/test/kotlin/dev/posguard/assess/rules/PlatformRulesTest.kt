package dev.posguard.assess.rules

import dev.posguard.assess.hits
import dev.posguard.assess.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlatformRulesTest {

    @Test
    fun `flags old targetSdk in gradle, catalogs, and manifests`() {
        val kts = source("app/build.gradle.kts", "android {\n  defaultConfig { targetSdk = 30 }\n}")
        val groovy = source("app/build.gradle", "defaultConfig {\n targetSdkVersion 28\n}")
        val toml = source("gradle/libs.versions.toml", "[versions]\ntargetSdk = \"33\"")
        val manifest = source("AndroidManifest.xml", "<uses-sdk android:targetSdkVersion=\"29\" />")
        val hits = OldTargetSdk.hits(kts, groovy, toml, manifest)
        assertEquals(
            listOf("targetSdk 30 is below 35.", "targetSdk 28 is below 35.", "targetSdk 33 is below 35.", "targetSdk 29 is below 35."),
            hits.map { it.detail },
        )
    }

    @Test
    fun `accepts current targetSdk, catalog references, and the minimum option`() {
        val current = source("build.gradle.kts", "targetSdk = 36\ntargetSdk = libs.versions.targetSdk.get().toInt()")
        assertEquals(emptyList<Any>(), OldTargetSdk.hits(current))
        val old = source("build.gradle.kts", "targetSdk = 30")
        assertEquals(emptyList<Any>(), OldTargetSdk.hits(old, options = mapOf("minimum" to "30")))
        assertThrows(IllegalArgumentException::class.java) { OldTargetSdk.hits(old, options = mapOf("minimum" to "high")) }
    }

    @Test
    fun `flags deprecated APIs once per file and skips imports`() {
        val file = source(
            "Legacy.java",
            """
            import android.os.AsyncTask;
            class Sync extends AsyncTask<Void, Void, Void> {}
            class Again extends AsyncTask<Void, Void, Void> {}
            Handler h = new Handler();
            ProgressDialog d = new ProgressDialog(this);
            SafetyNet.getClient(this).attest(nonce, key);
            Handler ok = new Handler(Looper.getMainLooper());
            """,
        )
        val hits = DeprecatedAndroidApi.hits(file)
        assertEquals(listOf(2, 5, 4, 6), hits.map { it.line })
    }

    @Test
    fun `flags debuggable manifests and debuggable release builds only`() {
        val manifest = source("AndroidManifest.xml", "<application android:debuggable=\"true\">")
        val gradle = source(
            "app/build.gradle.kts",
            """
            android {
                buildTypes {
                    debug {
                        isDebuggable = true
                    }
                    release {
                        isMinifyEnabled = true
                        isDebuggable = true
                    }
                }
                defaultConfig { }
            }
            """,
        )
        val hits = ManifestDebuggable.hits(manifest, gradle)
        assertEquals(listOf("AndroidManifest.xml:1", "app/build.gradle.kts:8"), hits.map { "${it.file}:${it.line}" })

        val groovy = source("build.gradle", "buildTypes {\n  release { debuggable true }\n}")
        assertEquals(1, ManifestDebuggable.hits(groovy).size)
        val clean = source("AndroidManifest.xml", "<!-- android:debuggable=\"true\" -->\n<application/>")
        assertEquals(emptyList<Any>(), ManifestDebuggable.hits(clean))
    }

    @Test
    fun `flags explicit and default backups`() {
        val explicit = source("AndroidManifest.xml", "<manifest>\n<application android:allowBackup=\"true\">")
        assertEquals("android:allowBackup is set to true.", ManifestAllowBackup.hits(explicit).single().detail)
        val implicit = source("AndroidManifest.xml", "<manifest>\n  <application\n    android:label=\"x\">")
        assertEquals(2, ManifestAllowBackup.hits(implicit).single().line)
        val off = source("AndroidManifest.xml", "<application\n android:allowBackup=\"false\">")
        val rules = source("AndroidManifest.xml", "<application android:dataExtractionRules=\"@xml/rules\">")
        val library = source("AndroidManifest.xml", "<manifest package=\"x\" />")
        assertEquals(emptyList<Any>(), ManifestAllowBackup.hits(off, rules, library))
    }

    @Test
    fun `flags cleartext settings and http literals but not local or namespace URLs`() {
        val manifest = source("AndroidManifest.xml", "<application android:usesCleartextTraffic=\"true\">")
        val config = source("res/xml/network_security_config.xml", "<base-config cleartextTrafficPermitted=\"true\" />")
        val code = source(
            "Api.kt",
            """
            val base = "http://pos-backend.example.com/api"
            val local = "http://10.0.2.2:8080"
            val secure = "https://pos-backend.example.com"
            """,
        )
        val hits = CleartextTraffic.hits(manifest, config, code)
        assertEquals(listOf("AndroidManifest.xml:1", "res/xml/network_security_config.xml:1", "Api.kt:1"), hits.map { "${it.file}:${it.line}" })
        assertEquals("Plain http:// URL.", hits.last().detail)
    }
}
