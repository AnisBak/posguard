package dev.posguard.ledger

import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.ledger.TxnState
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LedgerRecoveryWorkerTest {

    private suspend fun runWorker(ledger: TransactionLedger): ListenableWorker.Result =
        TestListenableWorkerBuilder<LedgerRecoveryWorker>(context)
            .setWorkerFactory(LedgerWorkerFactory(ledger))
            .build()
            .doWork()

    @Test
    fun `succeeds when everything resolves`() = runTest {
        val engine = FakeEngineStatus()
        val ledger = testLedger(engine)
        val id = ledger.begin(Money(500, "USD"), Tender.CARD)
        ledger.markSubmitted(id)
        engine.statuses[ledger.get(id)!!.idempotencyKey] = EngineStatus.Authorized("eng-w")

        assertEquals(ListenableWorker.Result.success(), runWorker(ledger))
        assertEquals(TxnState.AUTHORIZED, ledger.get(id)!!.state)
    }

    @Test
    fun `succeeds with nothing pending`() = runTest {
        assertEquals(ListenableWorker.Result.success(), runWorker(testLedger()))
    }

    @Test
    fun `retries while the engine is unreachable`() = runTest {
        val ledger = testLedger(FakeEngineStatus())
        val id = ledger.begin(Money(500, "USD"), Tender.CARD)
        ledger.markSubmitted(id)

        assertEquals(ListenableWorker.Result.retry(), runWorker(ledger))
    }

    @Test
    fun `factory ignores other workers`() {
        val factory = LedgerWorkerFactory(testLedger())
        val params = mockk<WorkerParameters>()
        assertNull(factory.createWorker(context, "com.example.OtherWorker", params))
    }

    @Test
    fun `schedule enqueues unique work that requires a network`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        LedgerRecovery.schedule(context)
        LedgerRecovery.schedule(context) // KEEP: still one pending request

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LedgerRecovery.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }
}
