package dev.posguard.ledger.internal

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One journal row. The columns are limited on purpose (CLAUDE.md §7). There is
 * no column for card numbers, PINs, track data, or CVV, and none may ever be
 * added.
 */
@Entity(
    tableName = "posguard_txn",
    indices = [Index(value = ["idempotencyKey"], unique = true), Index(value = ["state"])],
)
internal data class TransactionEntity(
    @PrimaryKey val id: String,
    val idempotencyKey: String,
    val amountCents: Long,
    val currency: String,
    val tender: String,
    val state: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val engineReference: String?,
    /** Set by recovery when the engine confirmed it never processed this key. */
    val resubmitAllowed: Boolean,
    /** Optimistic-lock version. Every write must match the version it read. */
    val version: Long,
)

@Dao
internal interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TransactionEntity)

    @Query("SELECT * FROM posguard_txn WHERE id = :id")
    suspend fun get(id: String): TransactionEntity?

    @Query("SELECT state FROM posguard_txn WHERE id = :id")
    fun observeState(id: String): Flow<String?>

    // rowid breaks ties between rows created in the same millisecond (insertion order).
    @Query("SELECT * FROM posguard_txn ORDER BY createdAtMillis DESC, rowid DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM posguard_txn WHERE state IN (:states) ORDER BY createdAtMillis, id")
    suspend fun findByStates(states: List<String>): List<TransactionEntity>

    /** Compare-and-set write. Returns 0 if the row changed since it was read. */
    @Query(
        """
        UPDATE posguard_txn
        SET state = :state,
            updatedAtMillis = :updatedAtMillis,
            engineReference = :engineReference,
            resubmitAllowed = :resubmitAllowed,
            version = :expectedVersion + 1
        WHERE id = :id AND version = :expectedVersion
        """,
    )
    suspend fun compareAndSet(
        id: String,
        expectedVersion: Long,
        state: String,
        updatedAtMillis: Long,
        engineReference: String?,
        resubmitAllowed: Boolean,
    ): Int
}

@Database(entities = [TransactionEntity::class], version = 1, exportSchema = true)
internal abstract class LedgerDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}
