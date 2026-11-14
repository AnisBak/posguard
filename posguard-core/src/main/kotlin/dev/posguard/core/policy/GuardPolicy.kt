package dev.posguard.core.policy

import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.ReasonCode
import java.util.Properties

/**
 * Maps each [ReasonCode] to a [GuardAction]. Policy is owned by the
 * integrating app. The library ships safe, documented [Defaults] and contains
 * no tuned thresholds (see docs/policy-defaults.md).
 *
 * Every reason code always has an action, so a policy can never leave a
 * reason undecided.
 */
public class GuardPolicy private constructor(
    private val actions: Map<ReasonCode, GuardAction>,
) {
    /** The action configured for [reason]. */
    public fun actionFor(reason: ReasonCode): GuardAction = actions.getValue(reason)

    /** Read-only view of the full mapping. */
    public fun asMap(): Map<ReasonCode, GuardAction> = actions

    /** Returns a builder seeded with this policy's actions. */
    public fun toBuilder(): Builder = Builder(actions)

    override fun equals(other: Any?): Boolean = other is GuardPolicy && other.actions == actions
    override fun hashCode(): Int = actions.hashCode()
    override fun toString(): String = "GuardPolicy($actions)"

    public class Builder internal constructor(initial: Map<ReasonCode, GuardAction>) {
        private val actions = initial.toMutableMap()

        /**
         * Sets the action for [reason].
         *
         * @throws IllegalArgumentException if [reason] is above its maximum action
         *   (see [maxActionFor]).
         */
        public fun set(reason: ReasonCode, action: GuardAction): Builder = apply {
            val max = maxActionFor(reason)
            require(action <= max) {
                "$reason cannot be set to $action (maximum is $max)."
            }
            actions[reason] = action
        }

        public fun build(): GuardPolicy = GuardPolicy(actions.toMap())
    }

    public companion object {
        /** Prefix for policy keys in [fromProperties]. */
        public const val PROPERTY_PREFIX: String = "posguard.policy."

        /**
         * Conservative public defaults. See docs/policy-defaults.md for the reason
         * behind each value.
         */
        public val Defaults: GuardPolicy = GuardPolicy(
            mapOf(
                ReasonCode.DEVICE_INTEGRITY_FAIL to GuardAction.BLOCK,
                ReasonCode.APP_INTEGRITY_FAIL to GuardAction.BLOCK,
                ReasonCode.ROOT_OR_EMULATOR_SUSPECTED to GuardAction.WARN,
                ReasonCode.SCREEN_OVERLAY_PRESENT to GuardAction.BLOCK,
                ReasonCode.SCREEN_CAPTURE_ACTIVE to GuardAction.BLOCK,
                ReasonCode.RISKY_ACCESSIBILITY_SERVICE to GuardAction.WARN,
                ReasonCode.REMOTE_CONTROL_RISK to GuardAction.BLOCK,
                ReasonCode.DEBUGGER_ATTACHED to GuardAction.BLOCK,
                ReasonCode.RELAY_RISK_PATTERN to GuardAction.WARN,
                ReasonCode.INTEGRITY_UNAVAILABLE to GuardAction.WARN,
            ).also { check(it.keys == ReasonCode.entries.toSet()) { "Defaults must cover every ReasonCode" } },
        )

        /** Starts from [Defaults]. */
        public fun builder(): Builder = Defaults.toBuilder()

        /**
         * The strictest action a reason may be given.
         *
         * [ReasonCode.RELAY_RISK_PATTERN] is capped at [GuardAction.WARN]: relay
         * flags are experimental and must never decline a payment on their own.
         */
        public fun maxActionFor(reason: ReasonCode): GuardAction = when (reason) {
            ReasonCode.RELAY_RISK_PATTERN -> GuardAction.WARN
            else -> GuardAction.BLOCK
        }

        /**
         * Builds a policy from [Defaults] plus overrides, for example loaded from
         * a config file:
         *
         * ```
         * posguard.policy.SCREEN_CAPTURE_ACTIVE=WARN
         * posguard.policy.ROOT_OR_EMULATOR_SUSPECTED=BLOCK
         * ```
         *
         * Parsing is strict so a typo cannot silently weaken protection. Keys
         * without [PROPERTY_PREFIX] are ignored.
         *
         * @throws IllegalArgumentException on an unknown reason code, an unknown
         *   action, or an action above the reason's maximum.
         */
        public fun fromProperties(properties: Properties): GuardPolicy {
            val builder = builder()
            for (key in properties.stringPropertyNames().sorted()) {
                if (!key.startsWith(PROPERTY_PREFIX)) continue
                val code = key.removePrefix(PROPERTY_PREFIX)
                val reason = requireNotNull(ReasonCode.fromCode(code)) {
                    "Unknown reason code in policy key '$key'."
                }
                val rawAction = properties.getProperty(key).trim()
                val action = requireNotNull(GuardAction.entries.firstOrNull { it.name == rawAction.uppercase() }) {
                    "Unknown action '$rawAction' for '$key'. Expected one of ${GuardAction.entries}."
                }
                builder.set(reason, action)
            }
            return builder.build()
        }
    }
}
