package dev.posguard.ledger

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.posguard.core.ledger.RecoveryOutcome
import dev.posguard.core.ledger.TransactionLedger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background recovery on reconnect. Schedules [LedgerRecoveryWorker], which
 * runs [TransactionLedger.recoverPending] once the device has a network
 * connection and retries with backoff while any transaction is still
 * unresolved.
 *
 * One-time setup: register [LedgerWorkerFactory] with WorkManager, for example
 * through `Configuration.Provider` in your `Application`, adding it to a
 * `DelegatingWorkerFactory` if you already have factories:
 *
 * ```
 * override val workManagerConfiguration = Configuration.Builder()
 *     .setWorkerFactory(LedgerWorkerFactory(ledger))
 *     .build()
 * ```
 *
 * Also call `recoverPending()` directly on app start. This worker covers
 * reconnects and outcomes that are still unresolved.
 */
public object LedgerRecovery {

    public const val UNIQUE_WORK_NAME: String = "posguard-ledger-recovery"

    private const val INITIAL_BACKOFF_SECONDS = 30L

    /** Enqueues recovery to run when connected. Does nothing if it is already pending. */
    public fun schedule(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request())
    }

    internal fun request(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<LedgerRecoveryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
}

/** Runs ledger recovery. Created by [LedgerWorkerFactory]. */
public class LedgerRecoveryWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val ledger: TransactionLedger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recovered = try {
            ledger.recoverPending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        return if (recovered.any { it.outcome == RecoveryOutcome.UNRESOLVED }) Result.retry() else Result.success()
    }
}

/**
 * Supplies [ledger] to [LedgerRecoveryWorker] without a global singleton or
 * DI framework. Returns null for other workers, so it can sit inside a
 * `DelegatingWorkerFactory`.
 */
public class LedgerWorkerFactory(private val ledger: TransactionLedger) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == LedgerRecoveryWorker::class.java.name) {
            LedgerRecoveryWorker(appContext, workerParameters, ledger)
        } else {
            null
        }
}
