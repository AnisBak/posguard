package dev.posguard.assess

/**
 * One check. Built-in rules live in `dev.posguard.assess.rules`. Other rule
 * packs plug in through [java.util.ServiceLoader]: implement this interface,
 * list the class in `META-INF/services/dev.posguard.assess.AssessRule`, and
 * put the jar on the classpath. No fork needed.
 *
 * Rules see source text only. They are heuristics, not proof: say so in
 * [whyItMatters] when false positives are likely, and never put string
 * literals or other values from the scanned code into [RuleHit.detail].
 */
public interface AssessRule {
    /** Stable, lowercase, `-`-separated id. Used in config keys and reports. */
    public val id: String
    public val title: String
    public val masvs: MasvsCategory
    public val defaultRisk: Risk
    public val defaultEase: Ease
    public val whyItMatters: String
    public val howToFix: String

    /** The posguard module that helps fix this, if any (for example `posguard-ledger`). */
    public val posguardModule: String? get() = null

    /** Option name → description. Set as `posguard.assess.rule.<id>.<option>`. Unknown options are rejected. */
    public val options: Map<String, String> get() = emptyMap()

    public fun check(context: RuleContext): List<RuleHit>
}

/** What a rule gets to look at. */
public class RuleContext(
    public val project: ProjectView,
    /** Options set for this rule in config. Keys are option names without the prefix. */
    public val options: Map<String, String> = emptyMap(),
)

/** Read-only view of the scanned files. */
public class ProjectView(public val files: List<SourceFile>) {
    /** Files of the given kinds. */
    public fun filesOf(vararg kinds: SourceKind): List<SourceFile> = files.filter { it.kind in kinds }

    /** Kotlin and Java files. */
    public val codeFiles: List<SourceFile> by lazy { filesOf(SourceKind.KOTLIN, SourceKind.JAVA) }

    /** Every (file, 1-based line number) in [candidates] whose comment-free code matches [regex]. */
    public fun matches(regex: Regex, candidates: List<SourceFile> = files): List<Pair<SourceFile, Int>> =
        candidates.flatMap { file ->
            file.code.mapIndexedNotNull { index, line -> if (regex.containsMatchIn(line)) file to index + 1 else null }
        }

    /** True if any file in [candidates] matches [regex] outside comments. */
    public fun anyMatch(regex: Regex, candidates: List<SourceFile> = files): Boolean =
        candidates.any { it.containsCode(regex) }
}
