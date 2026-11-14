package dev.posguard.ledger

import android.content.Context
import androidx.room.Room
import dev.posguard.core.event.GuardEventListener
import dev.posguard.core.ledger.EngineStatusSource
import dev.posguard.core.ledger.TransactionLedger
import dev.posguard.ledger.internal.LedgerDatabase
import dev.posguard.ledger.internal.MIGRATION_1_2
import dev.posguard.ledger.internal.RoomTransactionLedger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.util.UUID

/**
 * Creates the Room-backed, outage-safe [TransactionLedger].
 *
 * ```
 * val ledger = PosGuardLedger.Builder(context, engineAdapter).build()
 * ledger.recoverPending() // on app start, before new payments
 * ```
 *
 * Create **one instance per process** and reuse it, for example in your
 * `Application`. The journal never uses destructive migration: transactions
 * are never silently dropped on a schema change.
 */
public object PosGuardLedger {

    /** Default database file name. */
    public const val DEFAULT_DATABASE_NAME: String = "posguard-ledger.db"

    public class Builder(
        context: Context,
        private val engineStatusSource: EngineStatusSource,
    ) {
        private val appContext = context.applicationContext
        private var databaseName: String? = DEFAULT_DATABASE_NAME
        private var clock: Clock = Clock.systemUTC()
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO
        private val listeners = mutableListOf<GuardEventListener>()
        private var idGenerator: () -> String = { UUID.randomUUID().toString() }

        /** Database file name. Defaults to [DEFAULT_DATABASE_NAME]. */
        public fun databaseName(name: String): Builder = apply {
            require(name.isNotBlank()) { "databaseName must not be blank." }
            databaseName = name
        }

        /** Keeps the journal in memory only. For tests and demo resets. **Not crash-safe.** */
        public fun inMemory(): Builder = apply { databaseName = null }

        /** Clock for timestamps. Defaults to the UTC system clock. */
        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Dispatcher for journal I/O. Defaults to [Dispatchers.IO]. */
        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        /** Receives a `TxnStateChanged` event after each committed state change. */
        public fun addListener(listener: GuardEventListener): Builder = apply { listeners += listener }

        /** Test hook for deterministic ids. */
        internal fun idGenerator(generator: () -> String): Builder = apply { idGenerator = generator }

        public fun build(): TransactionLedger {
            val name = databaseName
            val db = if (name == null) {
                Room.inMemoryDatabaseBuilder(appContext, LedgerDatabase::class.java)
            } else {
                Room.databaseBuilder(appContext, LedgerDatabase::class.java, name)
            }.addMigrations(MIGRATION_1_2).build()
            return RoomTransactionLedger(
                db = db,
                engineStatus = engineStatusSource,
                clock = clock,
                dispatcher = dispatcher,
                listeners = listeners.toList(),
                newId = idGenerator,
            )
        }
    }
}
