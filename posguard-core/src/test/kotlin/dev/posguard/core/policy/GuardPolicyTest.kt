package dev.posguard.core.policy

import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.GuardAction.BLOCK
import dev.posguard.core.verdict.GuardAction.WARN
import dev.posguard.core.verdict.ReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class GuardPolicyTest {

    @Test
    fun `defaults match documented values`() {
        val expected = mapOf(
            ReasonCode.DEVICE_INTEGRITY_FAIL to BLOCK,
            ReasonCode.APP_INTEGRITY_FAIL to BLOCK,
            ReasonCode.ROOT_OR_EMULATOR_SUSPECTED to WARN,
            ReasonCode.SCREEN_OVERLAY_PRESENT to BLOCK,
            ReasonCode.SCREEN_CAPTURE_ACTIVE to BLOCK,
            ReasonCode.RISKY_ACCESSIBILITY_SERVICE to WARN,
            ReasonCode.REMOTE_CONTROL_RISK to BLOCK,
            ReasonCode.DEBUGGER_ATTACHED to BLOCK,
            ReasonCode.RELAY_RISK_PATTERN to WARN,
            ReasonCode.INTEGRITY_UNAVAILABLE to WARN,
        )
        assertEquals(expected, GuardPolicy.Defaults.asMap())
    }

    @Test
    fun `defaults cover every reason code`() {
        ReasonCode.entries.forEach { GuardPolicy.Defaults.actionFor(it) }
        assertEquals(ReasonCode.entries.toSet(), GuardPolicy.Defaults.asMap().keys)
    }

    @Test
    fun `builder overrides a single reason and keeps the rest`() {
        val policy = GuardPolicy.builder()
            .set(ReasonCode.ROOT_OR_EMULATOR_SUSPECTED, BLOCK)
            .build()

        assertEquals(BLOCK, policy.actionFor(ReasonCode.ROOT_OR_EMULATOR_SUSPECTED))
        ReasonCode.entries.filter { it != ReasonCode.ROOT_OR_EMULATOR_SUSPECTED }.forEach {
            assertEquals(GuardPolicy.Defaults.actionFor(it), policy.actionFor(it))
        }
    }

    @Test
    fun `builder does not mutate defaults`() {
        GuardPolicy.builder().set(ReasonCode.SCREEN_CAPTURE_ACTIVE, GuardAction.ALLOW).build()
        assertEquals(BLOCK, GuardPolicy.Defaults.actionFor(ReasonCode.SCREEN_CAPTURE_ACTIVE))
    }

    @Test
    fun `relay risk cannot be set to block`() {
        assertThrows(IllegalArgumentException::class.java) {
            GuardPolicy.builder().set(ReasonCode.RELAY_RISK_PATTERN, BLOCK)
        }
    }

    @Test
    fun `relay risk can be lowered to allow`() {
        val policy = GuardPolicy.builder().set(ReasonCode.RELAY_RISK_PATTERN, GuardAction.ALLOW).build()
        assertEquals(GuardAction.ALLOW, policy.actionFor(ReasonCode.RELAY_RISK_PATTERN))
    }

    @Test
    fun `only relay risk has a cap below block`() {
        ReasonCode.entries.forEach {
            val expected = if (it == ReasonCode.RELAY_RISK_PATTERN) WARN else BLOCK
            assertEquals(expected, GuardPolicy.maxActionFor(it))
        }
    }

    @Test
    fun `fromProperties applies overrides on top of defaults`() {
        val props = Properties().apply {
            setProperty("posguard.policy.SCREEN_CAPTURE_ACTIVE", "WARN")
            setProperty("posguard.policy.ROOT_OR_EMULATOR_SUSPECTED", " block ")
            setProperty("unrelated.key", "whatever")
        }
        val policy = GuardPolicy.fromProperties(props)

        assertEquals(WARN, policy.actionFor(ReasonCode.SCREEN_CAPTURE_ACTIVE))
        assertEquals(BLOCK, policy.actionFor(ReasonCode.ROOT_OR_EMULATOR_SUSPECTED))
        assertEquals(BLOCK, policy.actionFor(ReasonCode.SCREEN_OVERLAY_PRESENT))
    }

    @Test
    fun `fromProperties with no policy keys returns defaults`() {
        assertEquals(GuardPolicy.Defaults, GuardPolicy.fromProperties(Properties()))
    }

    @Test
    fun `fromProperties rejects unknown reason code`() {
        val props = Properties().apply { setProperty("posguard.policy.SCREEN_CAPTUR_ACTIVE", "WARN") }
        val e = assertThrows(IllegalArgumentException::class.java) { GuardPolicy.fromProperties(props) }
        assertTrue(e.message!!.contains("SCREEN_CAPTUR_ACTIVE"))
    }

    @Test
    fun `fromProperties rejects unknown action`() {
        val props = Properties().apply { setProperty("posguard.policy.SCREEN_CAPTURE_ACTIVE", "DENY") }
        assertThrows(IllegalArgumentException::class.java) { GuardPolicy.fromProperties(props) }
    }

    @Test
    fun `fromProperties enforces relay cap`() {
        val props = Properties().apply { setProperty("posguard.policy.RELAY_RISK_PATTERN", "BLOCK") }
        assertThrows(IllegalArgumentException::class.java) { GuardPolicy.fromProperties(props) }
    }
}
