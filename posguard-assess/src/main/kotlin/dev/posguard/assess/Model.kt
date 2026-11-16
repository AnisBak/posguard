package dev.posguard.assess

/**
 * OWASP MASVS (Mobile Application Security Verification Standard) control
 * groups, by name. Each finding maps to one group so teams can line the
 * report up with their own MASVS work.
 */
public enum class MasvsCategory(public val label: String) {
    STORAGE("MASVS-STORAGE"),
    CRYPTO("MASVS-CRYPTO"),
    AUTH("MASVS-AUTH"),
    NETWORK("MASVS-NETWORK"),
    PLATFORM("MASVS-PLATFORM"),
    CODE("MASVS-CODE"),
    RESILIENCE("MASVS-RESILIENCE"),
    PRIVACY("MASVS-PRIVACY"),
}

/** How much harm the gap can do in a payment app. */
public enum class Risk(internal val weight: Int) {
    HIGH(3),
    MEDIUM(2),
    LOW(1),
}

/** How much work the usual fix takes. */
public enum class Ease(internal val weight: Int) {
    EASY(3),
    MODERATE(2),
    HARD(1),
}

/**
 * Where a rule matched. [file] is relative to the scanned root, with `/`
 * separators. Both are null for project-wide findings (for example, "no
 * integrity check anywhere").
 */
public data class RuleHit(
    val file: String?,
    val line: Int?,
    /** Short, specific explanation. Must not quote string literals or values from the scanned code. */
    val detail: String,
)

/** One reported gap: a [RuleHit] plus its rule's metadata and effective risk and ease. */
public data class Finding(
    val ruleId: String,
    val title: String,
    val masvs: MasvsCategory,
    val risk: Risk,
    val ease: Ease,
    val file: String?,
    val line: Int?,
    val detail: String,
    val whyItMatters: String,
    val howToFix: String,
    val posguardModule: String?,
) {
    /** Risk × ease, 1 to 9. Higher means "fix this first": high risk and easy to fix. */
    val priority: Int get() = risk.weight * ease.weight

    /** `file:line`, `file`, or `(project)`. */
    val location: String get() = when {
        file == null -> "(project)"
        line == null -> file
        else -> "$file:$line"
    }
}

/** The result of one assessment. Deterministic: no timestamps or absolute paths. */
public data class Report(
    /** Display name of the scanned root (its directory name only). */
    val rootName: String,
    val filesScanned: Int,
    /** Rule ids that ran, in order. */
    val rulesRun: List<String>,
    /** Sorted by priority (highest first), then risk, rule id, and location. */
    val findings: List<Finding>,
) {
    /** Finding counts by risk, including zero counts. */
    public fun countsByRisk(): Map<Risk, Int> = Risk.entries.associateWith { r -> findings.count { it.risk == r } }

    /** True if any finding is at or above [threshold]. */
    public fun hasFindingsAtOrAbove(threshold: Risk): Boolean = findings.any { it.risk.weight >= threshold.weight }
}
