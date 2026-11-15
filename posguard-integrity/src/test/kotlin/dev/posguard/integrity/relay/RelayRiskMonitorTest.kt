package dev.posguard.integrity.relay

import dev.posguard.core.event.GuardEvent
import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.policy.GuardPolicy
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RelayRiskMonitorTest {

    private val t0 = Instant.parse("2026-11-15T12:00:00Z")

    /** A clock the test can move. */
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(t0)
    private val events = mutableListOf<GuardEvent>()
    private val config = RelayRiskConfig(walletBurstCount = 3, walletBurstWindow = 60.seconds)

    private fun monitor(
        rules: List<RelayRiskRule> = config.defaultRules(),
        cfg: RelayRiskConfig = config,
    ) = RelayRiskMonitor(cfg, rules, listOf(GuardEventListener { e -> events += e }), clock)

    private fun wallet(id: String, secondsAfter: Long) =
        TapMetadata(TxnId(id), t0.plusSeconds(secondsAfter), Money(500, "USD"), EntryMode.CONTACTLESS_WALLET)

    @Test
    fun `third wallet tap is flagged, queued, and emitted`() {
        val m = monitor()
        assertEquals(RelayRiskAssessment.Clear, m.record(wallet("a", 0)))
        assertEquals(RelayRiskAssessment.Clear, m.record(wallet("b", 10)))
        clock.now = t0.plusSeconds(20)
        val result = m.record(wallet("c", 20))

        assertTrue(result is RelayRiskAssessment.Flagged)
        assertEquals(listOf(WalletBurstRule.ID), (result as RelayRiskAssessment.Flagged).hits.map { it.ruleId })
        assertEquals(listOf(TxnId("c")), m.pendingReview.value.map { it.txnId })
        assertEquals(listOf(GuardEvent.RelayRiskFlagged(TxnId("c"), listOf(WalletBurstRule.ID), t0.plusSeconds(20))), events)
    }

    @Test
    fun `signal warns while a review is pending and clears after review`() = runTest {
        val m = monitor()
        assertEquals(SignalResult.Clear(RelayRiskMonitor.SIGNAL_ID), m.signal.check())

        listOf("a" to 0L, "b" to 10L, "c" to 20L).forEach { (id, s) -> m.record(wallet(id, s)) }
        val detected = m.signal.check()
        assertEquals(ReasonCode.RELAY_RISK_PATTERN, detected.reason)
        assertEquals("pending_review=1", (detected as SignalResult.Detected).detail)
        // Relay risk can never block: the policy caps it at WARN.
        assertEquals(GuardAction.WARN, GuardPolicy.maxActionFor(ReasonCode.RELAY_RISK_PATTERN))

        assertTrue(m.markReviewed(TxnId("c")))
        assertEquals(SignalResult.Clear(RelayRiskMonitor.SIGNAL_ID), m.signal.check())
        assertTrue(m.pendingReview.value.isEmpty())
        assertEquals(GuardEvent.RelayRiskReviewed(TxnId("c"), t0), events.last())
    }

    @Test
    fun `reviewing an unknown payment does nothing`() {
        val m = monitor()
        assertFalse(m.markReviewed(TxnId("nope")))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `recording the same payment twice does not count it twice`() {
        val m = monitor()
        m.record(wallet("a", 0))
        m.record(wallet("a", 0))
        assertEquals(RelayRiskAssessment.Clear, m.record(wallet("b", 10)))

        m.record(wallet("c", 20)) // flagged
        val again = m.record(wallet("c", 20))
        assertTrue(again is RelayRiskAssessment.Flagged)
        assertEquals(1, m.pendingReview.value.size)
        assertEquals(1, events.size)
    }

    @Test
    fun `history evicts by retention time`() {
        val cfg = config.copy(historyRetention = 2.minutes)
        val m = monitor(cfg = cfg, rules = listOf(CountingRule()))
        m.record(wallet("a", 0))
        clock.now = t0.plusSeconds(121)
        val result = m.record(wallet("b", 121)) as RelayRiskAssessment.Flagged
        assertEquals("seen=0", result.hits.single().explanation)
    }

    @Test
    fun `history evicts by size`() {
        val m = monitor(cfg = config.copy(historySize = 2), rules = listOf(CountingRule()))
        m.record(wallet("a", 0))
        m.record(wallet("b", 1))
        m.record(wallet("c", 2))
        val result = m.record(wallet("d", 3)) as RelayRiskAssessment.Flagged
        assertEquals("seen=2", result.hits.single().explanation)
    }

    @Test
    fun `a throwing rule is skipped and other rules still run`() {
        val broken = object : RelayRiskRule {
            override val id = "test.broken"
            override fun evaluate(current: TapMetadata, recent: List<TapMetadata>): RuleHit = error("boom")
        }
        val m = monitor(rules = listOf(broken, CountingRule()))
        val result = m.record(wallet("a", 0)) as RelayRiskAssessment.Flagged
        assertEquals(listOf("test.count"), result.hits.map { it.ruleId })
    }

    @Test
    fun `a throwing listener never breaks recording`() {
        val m = RelayRiskMonitor(config, listOf(CountingRule()), listOf(GuardEventListener { error("boom") }), clock)
        assertTrue(m.record(wallet("a", 0)) is RelayRiskAssessment.Flagged)
    }

    @Test
    fun `clear forgets history and pending reviews`() {
        val m = monitor()
        listOf("a" to 0L, "b" to 10L, "c" to 20L).forEach { (id, s) -> m.record(wallet(id, s)) }
        m.clear()
        assertTrue(m.pendingReview.value.isEmpty())
        assertEquals(RelayRiskAssessment.Clear, m.record(wallet("d", 30)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rule ids must be unique`() {
        monitor(rules = listOf(CountingRule(), CountingRule()))
    }

    /** Always fires, reporting how many earlier taps it was given. */
    private class CountingRule : RelayRiskRule {
        override val id = "test.count"
        override fun evaluate(current: TapMetadata, recent: List<TapMetadata>) = RuleHit(id, "seen=${recent.size}")
    }
}
