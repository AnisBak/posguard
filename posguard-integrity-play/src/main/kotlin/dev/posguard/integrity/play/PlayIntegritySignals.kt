package dev.posguard.integrity.play

import android.os.SystemClock
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns one Play Integrity verdict into posguard signals:
 *
 * | Signal id | Reason | Raised when |
 * |---|---|---|
 * | `play.device` | DEVICE_INTEGRITY_FAIL | no `MEETS_DEVICE_INTEGRITY` / `MEETS_STRONG_INTEGRITY` |
 * | `play.app` | APP_INTEGRITY_FAIL | `UNRECOGNIZED_VERSION` (`UNEVALUATED` is unavailable) |
 * | `play.access.capturing` | SCREEN_CAPTURE_ACTIVE | `KNOWN_`/`UNKNOWN_CAPTURING` |
 * | `play.access.overlays` | SCREEN_OVERLAY_PRESENT | `KNOWN_`/`UNKNOWN_OVERLAYS` |
 * | `play.access.controlling` | REMOTE_CONTROL_RISK | `KNOWN_`/`UNKNOWN_CONTROLLING` |
 *
 * All signals share **one** verdict fetch per preflight (single-flight, cached
 * for [ttl]). If the fetch fails, every signal reports unavailable.
 */
public object PlayIntegritySignals {

    public val DEFAULT_TTL: Duration = 10.seconds

    /**
     * @param includeAppAccessRisk set false if app access risk is not enabled in Play Console.
     *   Otherwise those three signals report unavailable on every preflight.
     */
    public fun create(
        source: IntegrityVerdictSource,
        includeAppAccessRisk: Boolean = true,
        ttl: Duration = DEFAULT_TTL,
    ): List<IntegritySignal> = create(source, includeAppAccessRisk, ttl, SystemClock::elapsedRealtime, ::randomRequestHash)

    internal fun create(
        source: IntegrityVerdictSource,
        includeAppAccessRisk: Boolean,
        ttl: Duration,
        nowMs: () -> Long,
        requestHash: () -> String,
    ): List<IntegritySignal> {
        val shared = SharedVerdict(source, ttl, nowMs, requestHash)
        val core = listOf(
            VerdictSignal("play.device", shared) { v ->
                val ok = IntegrityVerdict.MEETS_DEVICE_INTEGRITY in v.deviceRecognition ||
                    IntegrityVerdict.MEETS_STRONG_INTEGRITY in v.deviceRecognition
                if (ok) null else Finding(ReasonCode.DEVICE_INTEGRITY_FAIL, v.deviceRecognition.sorted().joinToString(",").ifEmpty { "no-labels" })
            },
            VerdictSignal("play.app", shared) { v ->
                when (v.appRecognition) {
                    IntegrityVerdict.PLAY_RECOGNIZED -> null
                    IntegrityVerdict.UNRECOGNIZED_VERSION -> Finding(ReasonCode.APP_INTEGRITY_FAIL, "unrecognized-version")
                    else -> Finding.Unavailable("app-unevaluated")
                }
            },
        )
        if (!includeAppAccessRisk) return core
        return core + listOf(
            accessRisk("play.access.capturing", shared, ReasonCode.SCREEN_CAPTURE_ACTIVE, "CAPTURING"),
            accessRisk("play.access.overlays", shared, ReasonCode.SCREEN_OVERLAY_PRESENT, "OVERLAYS"),
            accessRisk("play.access.controlling", shared, ReasonCode.REMOTE_CONTROL_RISK, "CONTROLLING"),
        )
    }

    private fun accessRisk(id: String, shared: SharedVerdict, reason: ReasonCode, category: String) =
        VerdictSignal(id, shared) { v ->
            val detected = v.appsDetected ?: return@VerdictSignal Finding.Unavailable("access-risk-not-evaluated")
            val kinds = listOf("KNOWN", "UNKNOWN").filter { "${it}_$category" in detected }
            if (kinds.isEmpty()) null else Finding(reason, kinds.joinToString(",") { it.lowercase() })
        }

    /** Random per-request hash (SHA-256 hex). Your backend checks it against `requestDetails.requestHash`. */
    private fun randomRequestHash(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(UUID.randomUUID().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** Result of evaluating one verdict aspect. Null from the evaluator means clear. */
internal open class Finding(val reason: ReasonCode?, val detail: String?) {
    class Unavailable(detail: String) : Finding(null, detail)
}

internal class VerdictSignal(
    override val id: String,
    private val shared: SharedVerdict,
    private val evaluate: (IntegrityVerdict) -> Finding?,
) : IntegritySignal {
    override suspend fun check(): SignalResult {
        val verdict = shared.get().getOrElse { return SignalResult.Unavailable(id, "verdict-unavailable") }
        val finding = try {
            evaluate(verdict)
        } catch (_: Exception) {
            return SignalResult.Unavailable(id, "verdict-parse-error")
        }
        return when {
            finding == null -> SignalResult.Clear(id)
            finding is Finding.Unavailable || finding.reason == null -> SignalResult.Unavailable(id, finding.detail)
            else -> SignalResult.Detected(id, finding.reason, finding.detail)
        }
    }
}

/** Single-flight, TTL-cached verdict shared by all Play signals. Failures are cached too, to avoid hammering. */
internal class SharedVerdict(
    private val source: IntegrityVerdictSource,
    private val ttl: Duration,
    private val nowMs: () -> Long,
    private val requestHash: () -> String,
) {
    private val lock = Mutex()
    private var cachedAt: Long = 0
    private var cached: Result<IntegrityVerdict>? = null

    suspend fun get(): Result<IntegrityVerdict> = lock.withLock {
        val now = nowMs()
        cached?.takeIf { now - cachedAt < ttl.inWholeMilliseconds }?.let { return@withLock it }
        val result = try {
            Result.success(source.verdict(requestHash()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        cached = result
        cachedAt = now
        result
    }
}
