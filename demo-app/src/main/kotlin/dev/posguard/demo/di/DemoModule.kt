package dev.posguard.demo.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.posguard.core.PosGuard
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.core.policy.GuardPolicy
import dev.posguard.demo.DemoThreats
import dev.posguard.integrity.IntegrityConfig
import dev.posguard.integrity.PosGuardIntegrity
import dev.posguard.integrity.relay.RelayRiskConfig
import dev.posguard.integrity.relay.RelayRiskMonitor
import dev.posguard.integrity.play.FakeIntegrityVerdictSource
import dev.posguard.integrity.play.PlayIntegritySignals
import dev.posguard.ledger.PosGuardLedger
import dev.posguard.mockengine.MockPaymentEngine
import dev.posguard.mockengine.SharedPreferencesMockProcessorStore
import dev.posguard.telemetry.DeviceContext
import dev.posguard.telemetry.InMemorySink
import dev.posguard.telemetry.JsonLinesFileSink
import dev.posguard.telemetry.PosGuardTelemetry
import java.io.File
import java.util.Properties
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Demo wiring. Hilt is used only in the demo app. The libraries use plain
 * builders, so this module is a readable example of integrating posguard.
 */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {

    const val DEMO_POLICY_ASSET: String = "demo-policy.properties"
    const val DEMO_RELAY_ASSET: String = "demo-relay.properties"

    @Provides @Singleton
    fun integrity(app: Application): PosGuardIntegrity = PosGuardIntegrity.install(app, IntegrityConfig())

    @Provides @Singleton
    fun eventSink(): InMemorySink = InMemorySink(capacity = 100)

    @Provides @Singleton
    fun telemetry(app: Application, sink: InMemorySink): PosGuardTelemetry =
        PosGuardTelemetry.Builder(DeviceContext.fromSystem(app))
            .addSink(sink)
            .addSink(JsonLinesFileSink(File(app.noBackupFilesDir, "posguard-telemetry")))
            .batchSize(1) // deliver immediately so the demo's event panel is live
            .build()

    /** DEMO — NOT A REAL PAYMENT ENGINE. Processor memory survives app kill (scenario 5). */
    @Provides @Singleton
    fun engine(app: Application): MockPaymentEngine = MockPaymentEngine.Builder()
        .store(SharedPreferencesMockProcessorStore(app))
        .latency(800.milliseconds)
        .build()

    @Provides @Singleton
    fun ledger(app: Application, engine: MockPaymentEngine, telemetry: PosGuardTelemetry): TransactionLedger =
        PosGuardLedger.Builder(app, engine).addListener(telemetry).build()

    /** Embedded mock integrity server: the demo works fully offline. */
    @Provides @Singleton
    fun integrityServer(): FakeIntegrityVerdictSource = FakeIntegrityVerdictSource()

    @Provides @Singleton
    fun threats(): DemoThreats = DemoThreats()

    @Provides @Singleton
    fun policy(app: Application): GuardPolicy =
        app.assets.open(DEMO_POLICY_ASSET).use { GuardPolicy.fromProperties(Properties().apply { load(it) }) }

    /** Experimental relay-risk flags, with demo-sized thresholds loaded from assets. */
    @Provides @Singleton
    fun relayRisk(app: Application, telemetry: PosGuardTelemetry): RelayRiskMonitor {
        val config = app.assets.open(DEMO_RELAY_ASSET).use { RelayRiskConfig.fromProperties(Properties().apply { load(it) }) }
        return RelayRiskMonitor(config, listeners = listOf(telemetry))
    }

    @Provides @Singleton
    fun posGuard(
        ledger: TransactionLedger,
        integrity: PosGuardIntegrity,
        integrityServer: FakeIntegrityVerdictSource,
        threats: DemoThreats,
        policy: GuardPolicy,
        telemetry: PosGuardTelemetry,
        relay: RelayRiskMonitor,
    ): PosGuard = PosGuard.Builder(ledger)
        .signals(integrity.signals())
        // Short TTL so Demo-controls toggles of the mock verdict take effect at once.
        .signals(PlayIntegritySignals.create(integrityServer, includeAppAccessRisk = true, ttl = 2.seconds))
        .signals(threats.signals)
        .signals(relay.signal) // warns while a flagged payment awaits review
        .policy(policy)
        .addListener(telemetry)
        .build()
}
