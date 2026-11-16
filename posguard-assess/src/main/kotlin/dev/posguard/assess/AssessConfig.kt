package dev.posguard.assess

import java.util.Properties

/**
 * Settings loaded from outside the library (a properties file). Public
 * defaults are documented in `docs/assess.md` and are not tuned to any
 * deployment.
 *
 * | Key | Value |
 * |---|---|
 * | `posguard.assess.include.tests` | `true` to also scan test source sets (default `false`) |
 * | `posguard.assess.rule.<id>.enabled` | `false` to turn a rule off |
 * | `posguard.assess.rule.<id>.risk` | `high`, `medium`, or `low` |
 * | `posguard.assess.rule.<id>.ease` | `easy`, `moderate`, or `hard` |
 * | `posguard.assess.rule.<id>.<option>` | a rule-specific option (see the rule's `options`) |
 *
 * [Assessor] checks rule ids and options against the rules that run.
 */
public data class AssessConfig(
    val includeTests: Boolean = false,
    val disabledRules: Set<String> = emptySet(),
    val riskOverrides: Map<String, Risk> = emptyMap(),
    val easeOverrides: Map<String, Ease> = emptyMap(),
    /** Rule id → (option name → value). */
    val ruleOptions: Map<String, Map<String, String>> = emptyMap(),
) {
    /** Every rule id this config mentions. */
    val mentionedRules: Set<String>
        get() = disabledRules + riskOverrides.keys + easeOverrides.keys + ruleOptions.keys

    public companion object {
        public const val PROPERTY_PREFIX: String = "posguard.assess."
        private const val RULE_PREFIX = "rule."
        private const val INCLUDE_TESTS = "include.tests"
        private val RULE_ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        /**
         * Reads keys with the `posguard.assess.` prefix. Other keys are ignored,
         * so one file can hold several configs.
         *
         * @throws IllegalArgumentException on unknown keys or malformed values.
         */
        public fun fromProperties(properties: Properties): AssessConfig {
            var includeTests = false
            val disabled = mutableSetOf<String>()
            val risks = mutableMapOf<String, Risk>()
            val eases = mutableMapOf<String, Ease>()
            val options = mutableMapOf<String, MutableMap<String, String>>()

            for (fullKey in properties.stringPropertyNames().filter { it.startsWith(PROPERTY_PREFIX) }.sorted()) {
                val key = fullKey.removePrefix(PROPERTY_PREFIX)
                val value = properties.getProperty(fullKey).trim()
                when {
                    key == INCLUDE_TESTS -> includeTests = parseBoolean(fullKey, value)
                    key.startsWith(RULE_PREFIX) -> {
                        val rest = key.removePrefix(RULE_PREFIX)
                        val dot = rest.indexOf('.')
                        require(dot > 0 && dot < rest.length - 1) {
                            "Expected $PROPERTY_PREFIX$RULE_PREFIX<id>.<setting>, got '$fullKey'."
                        }
                        val id = rest.substring(0, dot)
                        val setting = rest.substring(dot + 1)
                        require(RULE_ID.matches(id)) { "Bad rule id '$id' in '$fullKey'." }
                        when (setting) {
                            "enabled" -> if (!parseBoolean(fullKey, value)) disabled += id
                            "risk" -> risks[id] = parseEnum<Risk>(fullKey, value)
                            "ease" -> eases[id] = parseEnum<Ease>(fullKey, value)
                            else -> options.getOrPut(id) { mutableMapOf() }[setting] = value
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown assess key '$fullKey'.")
                }
            }
            return AssessConfig(includeTests, disabled, risks, eases, options)
        }

        private fun parseBoolean(key: String, value: String): Boolean = when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$key must be 'true' or 'false'.")
        }

        private inline fun <reified E : Enum<E>> parseEnum(key: String, value: String): E =
            enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "$key must be one of ${enumValues<E>().joinToString { it.name.lowercase() }}.",
                )
    }
}
