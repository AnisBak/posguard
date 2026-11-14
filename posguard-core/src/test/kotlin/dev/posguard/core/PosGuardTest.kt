@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.posguard.core

import dev.posguard.core.event.GuardEvent
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveredTxn
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TransactionRecord
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PosGuardTest {

    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun signal(id: String, block: suspend () -> SignalResult) = object : IntegritySignal {
        override val id = id
        override suspend fun check() = block()
    }

    private fun TestScope.guard(vararg signals: IntegritySignal) = PosGuard.Builder(NoopLedger)
        .signals(*signals)
        .dispatcher(StandardTestDispatcher(testScheduler))
        .clock(clock)

    @Test
    fun `no signals allows`() = runTest {
        val verdict = guard().build().preflight()
        assertTrue(verdict is GuardVerdict.Allow)
        assertEquals(now, verdict.evaluatedAt)
    }

    @Test
    fun `detected block reason blocks`() = runTest {
        val verdict = guard(
            signal("overlay") { SignalResult.Detected("overlay", ReasonCode.SCREEN_OVERLAY_PRESENT) },
            signal("capture") { SignalResult.Clear("capture") },
        ).build().preflight()

        assertTrue(verdict is GuardVerdict.Block)
        assertEquals(listOf(ReasonCode.SCREEN_OVERLAY_PRESENT), verdict.reasons)
        assertEquals(2, verdict.signals.size)
    }

    @Test
    fun `throwing signal becomes unavailable without leaking the message`() = runTest {
        val verdict = guard(
            signal("boom") { throw IllegalStateException("secret 4111111111111111") },
        ).build().preflight()

        assertTrue(verdict is GuardVerdict.Warn)
        assertEquals(listOf(ReasonCode.INTEGRITY_UNAVAILABLE), verdict.reasons)
        val result = verdict.signals.single() as SignalResult.Unavailable
        assertEquals("boom", result.signalId)
        assertEquals("error: IllegalStateException", result.detail)
    }

    @Test
    fun `slow signal times out as unavailable`() = runTest {
        val verdict = guard(
            signal("slow") { awaitCancellation() },
        ).signalTimeout(500.milliseconds).build().preflight()

        assertEquals(SignalResult.Unavailable("slow", "timeout"), verdict.signals.single())
        assertEquals(500L, testScheduler.currentTime)
    }

    @Test
    fun `signal throwing its own timeout is unavailable not a cancelled preflight`() = runTest {
        val verdict = guard(
            signal("inner") { withTimeout(10) { awaitCancellation() } },
        ).build().preflight()

        assertEquals(SignalResult.Unavailable("inner", "cancelled"), verdict.signals.single())
    }

    @Test
    fun `signals run in parallel`() = runTest {
        guard(
            signal("a") { delay(1.seconds); SignalResult.Clear("a") },
            signal("b") { delay(1.seconds); SignalResult.Clear("b") },
            signal("c") { delay(1.seconds); SignalResult.Clear("c") },
        ).build().preflight()

        assertEquals(1000L, testScheduler.currentTime)
    }

    @Test
    fun `cancelling the caller cancels preflight`() = runTest {
        val guard = guard(signal("slow") { delay(10.seconds); SignalResult.Clear("slow") }).build()
        var cancelled = false
        val job = launch {
            try {
                guard.preflight()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        testScheduler.advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()
        assertTrue(cancelled)
    }

    @Test
    fun `listeners receive the verdict and a throwing listener is isolated`() = runTest {
        val events = mutableListOf<GuardEvent>()
        val verdict = guard()
            .addListener { throw RuntimeException("telemetry down") }
            .addListener { events += it }
            .build()
            .preflight()

        val event = events.single() as GuardEvent.PreflightEvaluated
        assertSame(verdict, event.verdict)
        assertEquals(now, event.at)
    }

    @Test
    fun `duplicate signal ids are rejected`() {
        val builder = PosGuard.Builder(NoopLedger).signals(
            signal("same") { SignalResult.Clear("same") },
            signal("same") { SignalResult.Clear("same") },
        )
        assertThrows(IllegalArgumentException::class.java) { builder.build() }
    }

    @Test
    fun `non positive timeout is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PosGuard.Builder(NoopLedger).signalTimeout(0.seconds)
        }
    }

    @Test
    fun `ledger is exposed unchanged`() {
        assertSame(NoopLedger, PosGuard.Builder(NoopLedger).build().ledger)
    }

    private object NoopLedger : TransactionLedger {
        override suspend fun begin(amount: Money, tender: Tender): TxnId = error("unused")
        override suspend fun markSubmitted(id: TxnId) = error("unused")
        override suspend fun markResult(id: TxnId, result: EngineResult) = error("unused")
        override suspend fun markCompleted(id: TxnId) = error("unused")
        override suspend fun markVoided(id: TxnId) = error("unused")
        override suspend fun recoverPending(): List<RecoveredTxn> = error("unused")
        override suspend fun get(id: TxnId): TransactionRecord? = error("unused")
        override fun observe(id: TxnId): Flow<TxnState> = error("unused")
        override fun observeRecent(limit: Int): Flow<List<TransactionRecord>> = error("unused")
    }
}
