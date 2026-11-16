package dev.posguard.assess

import dev.posguard.assess.rules.BuiltInRules
import java.io.File
import java.util.ServiceLoader

/** Finds the rules to run: the built-in set plus any found through [ServiceLoader]. */
public object RuleRegistry {
    public fun builtIn(): List<AssessRule> = BuiltInRules.all()

    /** Built-in rules, then plugged-in rules sorted by id. */
    public fun discover(classLoader: ClassLoader = RuleRegistry::class.java.classLoader): List<AssessRule> {
        val plugged = ServiceLoader.load(AssessRule::class.java, classLoader).toList().sortedBy { it.id }
        return builtIn() + plugged
    }
}

/**
 * Runs [rules] over a source tree and ranks the findings.
 *
 * @throws IllegalArgumentException if rule ids repeat, or [config] names an
 *   unknown rule or option.
 */
public class Assessor(
    private val rules: List<AssessRule> = RuleRegistry.discover(),
    private val config: AssessConfig = AssessConfig(),
) {
    init {
        val duplicates = rules.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate rule ids: ${duplicates.sorted().joinToString()}." }
        val byId = rules.associateBy { it.id }
        val unknown = config.mentionedRules - byId.keys
        require(unknown.isEmpty()) { "Config names unknown rules: ${unknown.sorted().joinToString()}." }
        for ((id, opts) in config.ruleOptions) {
            val bad = opts.keys - byId.getValue(id).options.keys
            require(bad.isEmpty()) { "Rule '$id' has no options named: ${bad.sorted().joinToString()}." }
        }
    }

    /** Scans [root] (see [SourceTree.scan]) and assesses it. */
    public fun run(root: File): Report = assess(root.canonicalFile.name, SourceTree.scan(root, config.includeTests))

    /** Assesses already-read [files]. [rootName] is shown in the report. */
    public fun assess(rootName: String, files: List<SourceFile>): Report {
        val project = ProjectView(files)
        val active = rules.filter { it.id !in config.disabledRules }
        val findings = active.flatMap { rule ->
            val context = RuleContext(project, config.ruleOptions[rule.id].orEmpty())
            rule.check(context).distinct().map { hit ->
                Finding(
                    ruleId = rule.id,
                    title = rule.title,
                    masvs = rule.masvs,
                    risk = config.riskOverrides[rule.id] ?: rule.defaultRisk,
                    ease = config.easeOverrides[rule.id] ?: rule.defaultEase,
                    file = hit.file,
                    line = hit.line,
                    detail = hit.detail,
                    whyItMatters = rule.whyItMatters,
                    howToFix = rule.howToFix,
                    posguardModule = rule.posguardModule,
                )
            }
        }
        return Report(rootName, files.size, active.map { it.id }, rank(findings))
    }

    internal companion object {
        fun rank(findings: List<Finding>): List<Finding> = findings.sortedWith(
            compareByDescending<Finding> { it.priority }
                .thenByDescending { it.risk.weight }
                .thenBy { it.ruleId }
                .thenBy { it.file ?: "" }
                .thenBy { it.line ?: 0 },
        )
    }
}
