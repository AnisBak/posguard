package dev.posguard.core

import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.core.verdict.ReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VerdictEvaluatorTest {

    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val defaults = GuardPolicy.Defaults

    @Test
    fun `no signals allows`() {
        val verdict = VerdictEvaluator.evaluate(emptyList(), defaults, now)
        assertTrue(verdict is GuardVerdict.Allow)
        assertEquals(emptyList<ReasonCode>(), verdict.reasons)
        assertEquals(now, verdict.evaluatedAt)
    }

    @Test
    fun `all clear allows`() {
        val results = listOf(SignalResult.Clear("a"), SignalResult.Clear("b"))
        val verdict = VerdictEvaluator.evaluate(results, defaults, now)
        assertTrue(verdict is GuardVerdict.Allow)
        assertEquals(results, verdict.signals)
    }

    @Test
    fun `warn reason gives warn`() {
        val results = listOf(SignalResult.Detected("root", ReasonCode.ROOT_OR_EMULATOR_SUSPECTED))
        val verdict = VerdictEvaluator.evaluate(results, defaults, now)
        assertTrue(verdict is GuardVerdict.Warn)
        assertEquals(listOf(ReasonCode.ROOT_OR_EMULATOR_SUSPECTED), verdict.reasons)
    }

    @Test
    fun `strictest action wins and all reasons are kept`() {
        val results = listOf(
            SignalResult.Detected("root", ReasonCode.ROOT_OR_EMULATOR_SUSPECTED),
            SignalResult.Clear("capture"),
            SignalResult.Detected("overlay", ReasonCode.SCREEN_OVERLAY_PRESENT),
        )
        val verdict = VerdictEvaluator.evaluate(results, defaults, now)
        assertTrue(verdict is GuardVerdict.Block)
        assertEquals(
            listOf(ReasonCode.ROOT_OR_EMULATOR_SUSPECTED, ReasonCode.SCREEN_OVERLAY_PRESENT),
            verdict.reasons,
        )
    }

    @Test
    fun `unavailable maps to integrity unavailable and warns by default`() {
        val verdict = VerdictEvaluator.evaluate(listOf(SignalResult.Unavailable("play")), defaults, now)
        assertTrue(verdict is GuardVerdict.Warn)
        assertEquals(listOf(ReasonCode.INTEGRITY_UNAVAILABLE), verdict.reasons)
    }

    @Test
    fun `duplicate reasons are reported once`() {
        val results = listOf(
            SignalResult.Unavailable("a"),
            SignalResult.Unavailable("b"),
        )
        assertEquals(
            listOf(ReasonCode.INTEGRITY_UNAVAILABLE),
            VerdictEvaluator.evaluate(results, defaults, now).reasons,
        )
    }

    @Test
    fun `reason mapped to allow still allows but is reported`() {
        val policy = GuardPolicy.builder().set(ReasonCode.DEBUGGER_ATTACHED, GuardAction.ALLOW).build()
        val verdict = VerdictEvaluator.evaluate(
            listOf(SignalResult.Detected("dbg", ReasonCode.DEBUGGER_ATTACHED)),
            policy,
            now,
        )
        assertTrue(verdict is GuardVerdict.Allow)
        assertEquals(listOf(ReasonCode.DEBUGGER_ATTACHED), verdict.reasons)
    }

    @Test
    fun `detected cannot carry integrity unavailable`() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalResult.Detected("x", ReasonCode.INTEGRITY_UNAVAILABLE)
        }
    }

    @Test
    fun `reason codes round trip through stable code`() {
        ReasonCode.entries.forEach { assertEquals(it, ReasonCode.fromCode(it.code)) }
        assertEquals(null, ReasonCode.fromCode("NOPE"))
    }
}
