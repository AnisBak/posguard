package dev.posguard.assess

/** Builds an in-memory [SourceFile]; the kind comes from the file name. */
fun source(path: String, text: String): SourceFile =
    SourceFile(path, requireNotNull(SourceTree.kindOf(path.substringAfterLast('/'))) { "unknown kind: $path" }, text.trimIndent().lines())

fun project(vararg files: SourceFile): ProjectView = ProjectView(files.toList())

/** Runs [rule] over [files] with optional [options]. */
fun AssessRule.hits(vararg files: SourceFile, options: Map<String, String> = emptyMap()): List<RuleHit> =
    check(RuleContext(ProjectView(files.toList()), options))
