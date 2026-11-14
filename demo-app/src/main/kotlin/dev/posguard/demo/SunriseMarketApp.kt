package dev.posguard.demo

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.integrity.PosGuardIntegrity
import dev.posguard.ledger.LedgerWorkerFactory
import javax.inject.Inject

/**
 * Fictional "Sunrise Market" demo application. **DEMO — NOT A REAL PAYMENT.**
 *
 * Injecting [integrity] here installs the integrity monitors in `onCreate`,
 * before any activity starts. WorkManager gets the ledger through
 * [LedgerWorkerFactory] (the default initializer is removed in the manifest).
 */
@HiltAndroidApp
class SunriseMarketApp : Application(), Configuration.Provider {

    @Inject lateinit var integrity: PosGuardIntegrity

    @Inject lateinit var ledger: TransactionLedger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(LedgerWorkerFactory(ledger)).build()
}
