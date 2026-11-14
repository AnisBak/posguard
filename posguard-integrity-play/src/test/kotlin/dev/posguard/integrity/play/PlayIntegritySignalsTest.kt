package dev.posguard.integrity.play

import com.google.android.gms.tasks.Tasks
import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.RecoveredTxn
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TransactionRecord
import dev.posguard.core.ledger.TxnId
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.core.verdict.ReasonCode
import dev.posguard.integrity.play.IntegrityVerdict.Companion.HEALTHY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class PlayIntegritySignalsTest {

    private var nowMs = 0L
    private val source = FakeIntegrityVerdictSource()

    private fun signals(includeAccessRisk: Boolean = true) =
        PlayIntegritySignals.create(source, includeAccessRisk, 10.seconds, { nowMs }, { "hash" })

    private suspend fun results(verdict: IntegrityVerdict): Map<String, SignalResult> {
        source.verdict = verdict
        return signals().associate { it.id to it.check() }
    }

    @Test
    fun `healthy verdict clears every signal`() = runTest {
        results(HEALTHY).values.forEach { assertTrue("$it", it is SignalResult.Clear) }
    }

    @Test
    fun `missing device integrity label fails device integrity`() = runTest {
        val r = results(HEALTHY.copy(deviceRecognition = emptySet()))
        assertEquals(SignalResult.Detected("play.device", ReasonCode.DEVICE_INTEGRITY_FAIL, "no-labels"), r["play.device"])

        val basicOnly = results(HEALTHY.copy(deviceRecognition = setOf("MEETS_BASIC_INTEGRITY")))
        assertTrue(basicOnly["play.device"] is SignalResult.Detected)

        val strong = results(HEALTHY.copy(deviceRecognition = setOf(IntegrityVerdict.MEETS_STRONG_INTEGRITY)))
        assertEquals(SignalResult.Clear("play.device"), strong["play.device"])
    }

    @Test
    fun `app recognition maps to app integrity`() = runTest {
        assertEquals(
            SignalResult.Detected("play.app", ReasonCode.APP_INTEGRITY_FAIL, "unrecognized-version"),
            results(HEALTHY.copy(appRecognition = IntegrityVerdict.UNRECOGNIZED_VERSION))["play.app"],
        )
        assertEquals(
            SignalResult.Unavailable("play.app", "app-unevaluated"),
            results(HEALTHY.copy(appRecognition = IntegrityVerdict.UNEVALUATED))["play.app"],
        )
    }

    @Test
    fun `app access risk categories map to reason codes`() = runTest {
        val r = results(
            HEALTHY.copy(
                appsDetected = setOf(
                    IntegrityVerdict.KNOWN_CAPTURING,
                    IntegrityVerdict.UNKNOWN_OVERLAYS,
                    IntegrityVerdict.KNOWN_CONTROLLING,
                    IntegrityVerdict.UNKNOWN_CONTROLLING,
                ),
            ),
        )
        assertEquals(SignalResult.Detected("play.access.capturing", ReasonCode.SCREEN_CAPTURE_ACTIVE, "known"), r["play.access.capturing"])
        assertEquals(SignalResult.Detected("play.access.overlays", ReasonCode.SCREEN_OVERLAY_PRESENT, "unknown"), r["play.access.overlays"])
        assertEquals(
            SignalResult.Detected("play.access.controlling", ReasonCode.REMOTE_CONTROL_RISK, "known,unknown"),
            r["play.access.controlling"],
        )
    }

    @Test
    fun `access risk not evaluated is unavailable and can be excluded`() = runTest {
        val r = results(HEALTHY.copy(appsDetected = null))
        assertEquals(SignalResult.Unavailable("play.access.overlays", "access-risk-not-evaluated"), r["play.access.overlays"])
        assertEquals(listOf("play.device", "play.app"), signals(includeAccessRisk = false).map { it.id })
    }

    @Test
    fun `all signals share one fetch within the ttl`() = runTest {
        val all = signals()
        all.forEach { it.check() }
        assertEquals(1, source.calls)

        nowMs += 10_000 // TTL elapsed
        all.forEach { it.check() }
        assertEquals(2, source.calls)
    }

    @Test
    fun `fetch failure makes every signal unavailable and is not retried within the ttl`() = runTest {
        source.failure = IllegalStateException("network")
        val all = signals()
        all.forEach { assertEquals(SignalResult.Unavailable(it.id, "verdict-unavailable"), it.check()) }
        assertEquals(1, source.calls)
    }

    @Test
    fun `preflight with play signals blocks on an untrusted device`() = runTest {
        source.verdict = HEALTHY.copy(deviceRecognition = emptySet(), appsDetected = setOf(IntegrityVerdict.UNKNOWN_OVERLAYS))
        val verdict = PosGuard.Builder(StubLedger).signals(signals()).build().preflight()

        assertTrue(verdict is GuardVerdict.Block)
        assertEquals(setOf(ReasonCode.DEVICE_INTEGRITY_FAIL, ReasonCode.SCREEN_OVERLAY_PRESENT), verdict.reasons.toSet())
        assertEquals(1, source.calls)
    }

    // --- Task adapter --------------------------------------------------------------------

    @Test
    fun `task adapter returns results and propagates failures`() = runTest {
        assertEquals("ok", Tasks.forResult("ok").await())
        val error = runCatching { Tasks.forException<String>(IllegalStateException("boom")).await() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        val cancelled = runCatching { Tasks.forCanceled<String>().await() }.exceptionOrNull()
        assertTrue(cancelled is kotlinx.coroutines.CancellationException)
    }

    private object StubLedger : TransactionLedger {
        override suspend fun begin(amount: Money, tender: Tender): TxnId = error("unused")
        override suspend fun markSubmitted(id: TxnId) = error("unused")
        override suspend fun markResult(id: TxnId, result: EngineResult) = error("unused")
        override suspend fun markCompleted(id: TxnId) = error("unused")
        override suspend fun markVoided(id: TxnId) = error("unused")
        override suspend fun recoverPending(): List<RecoveredTxn> = error("unused")
        override suspend fun get(id: TxnId): TransactionRecord? = error("unused")
        override fun observe(id: TxnId): Flow<TxnState> = error("unused")
    }
}
