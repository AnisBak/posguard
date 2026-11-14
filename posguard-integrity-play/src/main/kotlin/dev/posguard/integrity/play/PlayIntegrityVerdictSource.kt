package dev.posguard.integrity.play

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real Play Integrity source using the **standard request** API.
 *
 * 1. `IntegrityManagerFactory.createStandard(context)`
 * 2. `prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(n))`.
 *    This is a warm-up server call, limited to 5 per minute per app instance. Call [warmUp] at app start.
 * 3. `provider.request(StandardIntegrityTokenRequest.builder().setRequestHash(hash))` takes
 *    a few hundred milliseconds.
 * 4. [verifier] sends the token to **your backend**, which decodes it.
 *
 * Requires Google Play services, Play Console setup, and a backend. See docs/integrity.md.
 * The cloud project number is configuration and is never committed.
 */
public class PlayIntegrityVerdictSource(
    context: Context,
    private val cloudProjectNumber: Long,
    private val verifier: IntegrityTokenVerifier,
) : IntegrityVerdictSource {

    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val providerLock = Mutex()

    @Volatile private var provider: StandardIntegrityTokenProvider? = null

    /** Prepares the token provider ahead of the first preflight. Safe to call repeatedly. */
    public suspend fun warmUp() {
        provider()
    }

    override suspend fun verdict(requestHash: String): IntegrityVerdict {
        val token = try {
            provider()
                .request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())
                .await()
                .token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The provider may have expired (INTEGRITY_TOKEN_PROVIDER_INVALID). Prepare again next time.
            provider = null
            throw e
        }
        return verifier.verify(token, requestHash)
    }

    private suspend fun provider(): StandardIntegrityTokenProvider = providerLock.withLock {
        provider ?: manager.prepareIntegrityToken(
            PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build(),
        ).await().also { provider = it }
    }
}

/** Awaits a Play services [Task] without extra libraries. Listeners run inline, not on the main thread. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    val inline = java.util.concurrent.Executor { it.run() }
    addOnSuccessListener(inline) { cont.resume(it) }
    addOnFailureListener(inline) { cont.resumeWithException(it) }
    addOnCanceledListener(inline) { cont.cancel() }
}
