package dev.posguard.ledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.event.GuardEvent
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.EngineStatusSource
import dev.posguard.core.ledger.IdempotencyKey
import dev.posguard.core.ledger.TransactionLedger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Fake engine status endpoint. Statuses default to [EngineStatus.Unreachable]. */
internal class FakeEngineStatus : EngineStatusSource {
    val statuses = mutableMapOf<IdempotencyKey, EngineStatus>()
    val queried = CopyOnWriteArrayList<IdempotencyKey>()
    var throwOnQuery: Exception? = null

    override suspend fun queryStatus(key: IdempotencyKey): EngineStatus {
        queried += key
        throwOnQuery?.let { throw it }
        return statuses[key] ?: EngineStatus.Unreachable
    }
}

internal val TEST_NOW: Instant = Instant.parse("2026-11-14T12:00:00Z")

internal val context: Context get() = ApplicationProvider.getApplicationContext()

internal fun testLedger(
    engine: FakeEngineStatus = FakeEngineStatus(),
    events: MutableList<GuardEvent> = CopyOnWriteArrayList(),
    databaseName: String? = null,
): TransactionLedger {
    val counter = AtomicInteger()
    return PosGuardLedger.Builder(context, engine)
        .clock(Clock.fixed(TEST_NOW, ZoneOffset.UTC))
        .addListener { events += it }
        .apply { if (databaseName == null) inMemory() else databaseName(databaseName) }
        .idGenerator { "id-${databaseName ?: "mem"}-${counter.incrementAndGet()}-${System.nanoTime()}" }
        .build()
}
