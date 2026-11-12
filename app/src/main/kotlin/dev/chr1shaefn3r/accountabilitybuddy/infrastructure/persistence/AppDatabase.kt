package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "limits")
data class LimitRow(
    @PrimaryKey val id: String,
    val name: String,
    val budgetSeconds: Long,
    val apps: String,
)

@Entity(tableName = "hits", primaryKeys = ["day", "limitId"])
data class HitRow(
    val day: String,
    val limitId: String,
    val limitName: String,
    val budgetSeconds: Long,
    val apps: String,
    val hitAt: Long,
)

@Entity(tableName = "pairings")
data class PairingRow(
    @PrimaryKey val id: String,
    val partnerRole: String,
    val partnerName: String,
    val partnerAddress: String,
    val state: String,
    val lastSignOfLife: Long,
    val nickname: String?,
    val instantNotifications: Boolean,
    val daySummary: Boolean,
    val silenceAlerted: Boolean,
    val nextCounter: Long,
)

/** The session keys of a pairing, encrypted with the Android Keystore. */
@Entity(tableName = "pairing_keys")
class PairingKeysRow(
    @PrimaryKey val pairingId: String,
    val protectedKeys: ByteArray,
)

/** The one open invitation. Its secrets are encrypted with the Android Keystore. */
@Entity(tableName = "invitation")
class InvitationRow(
    @PrimaryKey val slot: Int,
    val pairingId: String,
    val protectedSecret: ByteArray,
    val protectedPrivateKey: ByteArray,
    val publicKey: ByteArray,
    val expiresAt: Long,
)

@Entity(tableName = "outbox")
class OutboxRow(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val pairingId: String,
    val address: String,
    val wire: ByteArray,
    val createdAt: Long,
)

@Entity(tableName = "received", primaryKeys = ["pairingId", "counter"])
data class ReceivedRow(
    val pairingId: String,
    val counter: Long,
)

@Entity(tableName = "history")
data class HistoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val pairingId: String,
    val at: Long,
    val message: String,
)

@Dao
interface LimitDao {
    @Query("SELECT * FROM limits ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<LimitRow>>

    @Query("SELECT * FROM limits ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<LimitRow>

    @Query("SELECT * FROM limits WHERE id = :id")
    suspend fun find(id: String): LimitRow?

    @Upsert
    suspend fun save(row: LimitRow)

    @Query("DELETE FROM limits WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM hits WHERE day = :day")
    suspend fun hitsOn(day: String): List<HitRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: HitRow)

    @Query("DELETE FROM hits WHERE day < :day")
    suspend fun forgetHitsBefore(day: String)
}

@Dao
@Suppress("TooManyFunctions")
interface PairingDao {
    @Query("SELECT * FROM pairings ORDER BY partnerName COLLATE NOCASE")
    fun observeAll(): Flow<List<PairingRow>>

    @Query("SELECT * FROM pairings ORDER BY partnerName COLLATE NOCASE")
    suspend fun all(): List<PairingRow>

    @Query("SELECT * FROM pairings WHERE id = :id")
    suspend fun find(id: String): PairingRow?

    @Insert
    suspend fun insert(row: PairingRow)

    @Update
    suspend fun update(row: PairingRow)

    @Query("DELETE FROM pairings WHERE id = :id")
    suspend fun delete(id: String)

    @Upsert
    suspend fun saveKeys(row: PairingKeysRow)

    @Query("SELECT * FROM pairing_keys WHERE pairingId = :pairingId")
    suspend fun keysOf(pairingId: String): PairingKeysRow?

    @Query("DELETE FROM pairing_keys WHERE pairingId = :pairingId")
    suspend fun forgetKeys(pairingId: String)

    @Query("SELECT * FROM invitation WHERE slot = 0")
    suspend fun invitation(): InvitationRow?

    @Upsert
    suspend fun saveInvitation(row: InvitationRow)

    @Query("DELETE FROM invitation")
    suspend fun clearInvitation()
}

@Dao
@Suppress("TooManyFunctions")
interface MessageDao {
    @Insert
    suspend fun enqueue(row: OutboxRow)

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun pending(): List<OutboxRow>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun removeFromOutbox(id: Long)

    @Query("DELETE FROM outbox WHERE createdAt < :instant")
    suspend fun removeFromOutboxBefore(instant: Long)

    /** Returns -1 if the message was recorded before. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordReceived(row: ReceivedRow): Long

    @Query("DELETE FROM received WHERE pairingId = :pairingId")
    suspend fun forgetReceived(pairingId: String)

    @Insert
    suspend fun addToHistory(row: HistoryRow)

    @Query("SELECT * FROM history WHERE pairingId = :pairingId ORDER BY at DESC, id DESC")
    fun observeHistory(pairingId: String): Flow<List<HistoryRow>>

    @Query(
        "SELECT * FROM history WHERE id IN (SELECT MAX(id) FROM history GROUP BY pairingId)",
    )
    fun observeLatestHistory(): Flow<List<HistoryRow>>

    @Query("DELETE FROM history WHERE at < :instant")
    suspend fun forgetHistoryBefore(instant: Long)

    @Query("DELETE FROM history WHERE pairingId = :pairingId")
    suspend fun forgetHistory(pairingId: String)
}

@Database(
    entities = [
        LimitRow::class,
        HitRow::class,
        PairingRow::class,
        PairingKeysRow::class,
        InvitationRow::class,
        OutboxRow::class,
        ReceivedRow::class,
        HistoryRow::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun limits(): LimitDao

    abstract fun pairings(): PairingDao

    abstract fun messages(): MessageDao
}
