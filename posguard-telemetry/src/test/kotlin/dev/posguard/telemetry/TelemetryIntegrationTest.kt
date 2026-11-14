package dev.posguard.telemetry

import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.EngineResult
import dev.posguard.core.ledger.EngineStatus
import dev.posguard.core.ledger.Money
import dev.posguard.core.ledger.Tender
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.ReasonCode
import dev.posguard.ledger.PosGuardLedger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Telemetry wired into PosGuard and the ledger, the way an app would wire it. */
@RunWith(RobolectricTestRunner::class)
class TelemetryIntegrationTest {

    @Test
    fun `preflight and ledger transitions arrive as telemetry events`() = runTest {
        val sink = InMemorySink()
        val telemetry = PosGuardTelemetry.Builder(DeviceContext("Acme", "Test", "15", 35, "0.1.0"))
            .addSink(sink)
            .scope(backgroundScope)
            .build()

        val ledger = PosGuardLedger.Builder(ApplicationProvider.getApplicationContext(), { EngineStatus.NotProcessed })
            .inMemory()
            .addListener(telemetry)
            .build()
        val guard = PosGuard.Builder(ledger)
            .signals(object : IntegritySignal {
                override val id = "root"
                override suspend fun check() = SignalResult.Detected(id, ReasonCode.ROOT_OR_EMULATOR_SUSPECTED)
            })
            .addListener(telemetry)
            .build()

        guard.preflight()
        val id = ledger.begin(Money(500, "USD"), Tender.CARD)
        ledger.markSubmitted(id)
        ledger.markResult(id, EngineResult.Authorized("eng-1"))
        telemetry.flush()

        val events = sink.events.value
        assertEquals(
            listOf(EventType.PREFLIGHT_EVALUATED) + List(3) { EventType.TXN_STATE_CHANGED },
            events.map { it.type },
        )
        assertEquals(GuardAction.WARN, events[0].verdict)
        assertEquals(listOf("ROOT_OR_EMULATOR_SUSPECTED"), events[0].reasonCodes)
        assertEquals(listOf("CREATED", "SUBMITTED", "AUTHORIZED"), events.drop(1).map { it.txn!!.to })
        // No amounts or engine references in telemetry: posguard events carry
        // no message, no attributes, and there is no amount field in the schema.
        events.forEach { e ->
            val json = e.toJson()
            assertEquals(false, json.contains("eng-1"))
            assertEquals(false, json.contains("amount", ignoreCase = true))
            assertEquals(null, e.message)
            assertEquals(emptyMap<String, String>(), e.attributes)
        }
    }
}
