package dev.posguard.assess

import java.io.File

/** What kind of file a [SourceFile] is. Decides which rules look at it. */
public enum class SourceKind {
    KOTLIN,
    JAVA,
    MANIFEST,
    XML,

    /** `*.gradle`, `*.gradle.kts`, and version catalogs (`*.toml`). */
    GRADLE,
}

/** One text file from the scanned tree. */
public class SourceFile(
    /** Relative to the scanned root, with `/` separators. */
    public val path: String,
    public val kind: SourceKind,
    /** Raw lines, as read. */
    public val lines: List<String>,
) {
    /**
     * [lines] with comments removed (Kotlin, Java, and Gradle files: `//` and
     * block comments; TOML: `#` lines; XML: `<!-- -->`). Line numbers are
     * kept, and string literals are kept as written.
     */
    public val code: List<String> by lazy { CommentStripper.strip(lines, kind) }

    /** True if [regex] matches anywhere in [code]. */
    public fun containsCode(regex: Regex): Boolean = code.any { regex.containsMatchIn(it) }

    override fun toString(): String = "SourceFile($path, $kind)"
}

/** Reads the files posguard-assess knows how to scan. */
public object SourceTree {
    /** Directory names never entered. */
    public val SKIPPED_DIRECTORIES: Set<String> = setOf(
        "build", ".git", ".gradle", ".idea", ".cxx", ".externalNativeBuild", "node_modules", "generated", "intermediates",
    )

    /** Test source sets, skipped unless `includeTests` is set. */
    public val TEST_DIRECTORIES: Set<String> = setOf("test", "androidTest", "testFixtures", "sharedTest")

    /** Files larger than this are skipped (most likely generated or vendored code). */
    public const val MAX_FILE_BYTES: Long = 1_000_000

    /** Classifies a file by name, or null if posguard-assess doesn't scan it. */
    public fun kindOf(name: String): SourceKind? = when {
        name == "AndroidManifest.xml" -> SourceKind.MANIFEST
        name.endsWith(".gradle.kts") || name.endsWith(".gradle") || name.endsWith(".toml") -> SourceKind.GRADLE
        name.endsWith(".kt") || name.endsWith(".kts") -> SourceKind.KOTLIN
        name.endsWith(".java") -> SourceKind.JAVA
        name.endsWith(".xml") -> SourceKind.XML
        else -> null
    }

    /** Walks [root] and reads every scannable file, sorted by path. */
    public fun scan(root: File, includeTests: Boolean = false): List<SourceFile> {
        require(root.isDirectory) { "Not a directory: ${root.name}" }
        val skipped = if (includeTests) SKIPPED_DIRECTORIES else SKIPPED_DIRECTORIES + TEST_DIRECTORIES
        val files = root.walkTopDown()
            .onEnter { it == root || it.name !in skipped }
            .filter { it.isFile }
            .toList()
        return read(root, files)
    }

    /**
     * Reads [files] that the caller already chose (for example, a Gradle task's
     * input file tree). Files outside [root] or of unknown kinds are ignored.
     */
    public fun read(root: File, files: Iterable<File>): List<SourceFile> {
        val rootCanonical = root.canonicalFile
        return files.mapNotNull { file ->
            val kind = kindOf(file.name) ?: return@mapNotNull null
            if (file.length() > MAX_FILE_BYTES) return@mapNotNull null
            val relative = file.canonicalFile.relativeToOrNull(rootCanonical) ?: return@mapNotNull null
            if (relative.path.startsWith("..")) return@mapNotNull null
            SourceFile(relative.invariantSeparatorsPath, kind, file.readLines(Charsets.UTF_8))
        }.sortedBy { it.path }
    }
}

internal object CommentStripper {
    fun strip(lines: List<String>, kind: SourceKind): List<String> = when (kind) {
        SourceKind.MANIFEST, SourceKind.XML -> stripXml(lines)
        SourceKind.KOTLIN, SourceKind.JAVA -> stripC(lines, hashComments = false)
        SourceKind.GRADLE -> stripC(lines, hashComments = true)
    }

    /** Removes line and block comments, leaving string and char literals alone. */
    private fun stripC(lines: List<String>, hashComments: Boolean): List<String> {
        var inBlock = false
        return lines.map { line ->
            if (hashComments && !inBlock && line.trimStart().startsWith("#")) return@map ""
            val out = StringBuilder(line.length)
            var quote: Char? = null
            var i = 0
            while (i < line.length) {
                val c = line[i]
                val next = line.getOrNull(i + 1)
                if (inBlock) {
                    if (c == '*' && next == '/') {
                        inBlock = false
                        i++
                    }
                } else if (quote != null) {
                    out.append(c)
                    if (c == '\\' && next != null) {
                        out.append(next)
                        i++
                    } else if (c == quote) {
                        quote = null
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c
                    out.append(c)
                } else if (c == '/' && next == '/') {
                    break
                } else if (c == '/' && next == '*') {
                    inBlock = true
                    i++
                } else {
                    out.append(c)
                }
                i++
            }
            out.toString()
        }
    }

    private fun stripXml(lines: List<String>): List<String> {
        var inComment = false
        return lines.map { line ->
            val out = StringBuilder(line.length)
            var i = 0
            while (i < line.length) {
                if (inComment) {
                    if (line.startsWith("-->", i)) {
                        inComment = false
                        i += 3
                    } else {
                        i++
                    }
                } else if (line.startsWith("<!--", i)) {
                    inComment = true
                    i += 4
                } else {
                    out.append(line[i])
                    i++
                }
            }
            out.toString()
        }
    }
}
