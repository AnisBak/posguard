package dev.posguard.assess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceTreeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, text: String = "x") {
        val file = temp.root.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `classifies files by name`() {
        assertEquals(SourceKind.MANIFEST, SourceTree.kindOf("AndroidManifest.xml"))
        assertEquals(SourceKind.GRADLE, SourceTree.kindOf("build.gradle.kts"))
        assertEquals(SourceKind.GRADLE, SourceTree.kindOf("build.gradle"))
        assertEquals(SourceKind.GRADLE, SourceTree.kindOf("libs.versions.toml"))
        assertEquals(SourceKind.KOTLIN, SourceTree.kindOf("Pay.kt"))
        assertEquals(SourceKind.JAVA, SourceTree.kindOf("Pay.java"))
        assertEquals(SourceKind.XML, SourceTree.kindOf("network_security_config.xml"))
        assertNull(SourceTree.kindOf("logo.png"))
    }

    @Test
    fun `scan skips build output, git, tests, and unknown files, and sorts by path`() {
        write("app/src/main/java/b/Pay.kt")
        write("app/src/main/AndroidManifest.xml")
        write("app/build/generated/Gen.kt")
        write(".git/config.xml")
        write("app/src/test/java/PayTest.kt")
        write("app/src/main/res/drawable/logo.png")
        write("build.gradle.kts")

        val paths = SourceTree.scan(temp.root).map { it.path }
        assertEquals(listOf("app/src/main/AndroidManifest.xml", "app/src/main/java/b/Pay.kt", "build.gradle.kts"), paths)

        val withTests = SourceTree.scan(temp.root, includeTests = true).map { it.path }
        assertEquals(true, "app/src/test/java/PayTest.kt" in withTests)
    }

    @Test
    fun `read ignores files outside the root`() {
        write("in/A.kt")
        val outside = temp.newFile("B.kt")
        val files = SourceTree.read(temp.root.resolve("in"), listOf(temp.root.resolve("in/A.kt"), outside))
        assertEquals(listOf("A.kt"), files.map { it.path })
    }

    @Test
    fun `code strips line and block comments but keeps strings and line numbers`() {
        val file = source(
            "A.kt",
            """
            val a = "http://x" // trailing
            /* start
               middle Log.d(pan)
            end */ val b = 1
            val c = '/' // c
            """,
        )
        assertEquals(listOf("val a = \"http://x\" ", "", "", " val b = 1", "val c = '/' "), file.code)
    }

    @Test
    fun `code strips xml comments and toml hash lines`() {
        val xml = source("AndroidManifest.xml", "<a/> <!-- android:debuggable=\"true\" -->\n<!--\nx\n--> <b/>")
        assertEquals(listOf("<a/> ", "", "", " <b/>"), xml.code)
        val toml = source("libs.versions.toml", "# targetSdk = \"21\"\ntargetSdk = \"36\"")
        assertEquals(listOf("", "targetSdk = \"36\""), toml.code)
    }
}
