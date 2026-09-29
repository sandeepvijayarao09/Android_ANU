package io.github.sandeepvijayarao09.anu.core.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.sandeepvijayarao09.anu.core.vault.KeyPolicy
import io.github.sandeepvijayarao09.anu.core.vault.KeystoreAead
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/** One transcript message (ADR-018). Tool calls and results are stored as JSON. */
@Entity(tableName = "messages", primaryKeys = ["conversationId", "seq"])
data class MessageEntity(
    val conversationId: String,
    val seq: Int,
    val role: String,
    val text: String,
    val toolCallsJson: String?,
    val toolResultsJson: String?,
    val createdAtMillis: Long,
    /** The ids of a user message's photos in `photos` (ADR-075), as a JSON array. Null when it has none. */
    val photosJson: String? = null,
    /**
     * A user message's voice note (ADR-078), as JSON: its words, length and whether it was shared or cut short. The
     * recording itself is never saved. Null when it has none.
     */
    val voiceNoteJson: String? = null,
    /** The screen a user message asked about (ADR-083), as JSON: its app and when. Never its picture. Null when none. */
    val screenJson: String? = null,
)

/** A photo the user attached, kept in the encrypted database with its message (G2, ADR-075). A JPEG, 1,024 px at most. */
@Entity(tableName = "photos")
class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bytes: ByteArray,
    val createdAtMillis: Long,
)

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun load(ids: List<Long>): List<PhotoEntity>

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

/** One Privacy Ledger entry (I3). It describes what was sent, never the content. */
@Entity(tableName = "ledger")
data class LedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val destination: String,
    val purpose: String,
    val dataClasses: String,
    val redactedJson: String,
    val characters: Int,
)

/** One use of a vault secret (§11): which item, by whom, for which host. Never the value. */
@Entity(tableName = "vault_audit")
data class VaultAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val caller: String,
    val host: String,
    val atMillis: Long,
)

/** One tool call as the Tool Broker decided it (§7.2). Shown to the user under Activity. */
@Entity(tableName = "tool_audit")
data class ToolAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val toolName: String,
    val risk: String,
    val summary: String,
    val outcome: String,
    val approved: Boolean?,
    val atMillis: Long,
)

/** The result of a side-effecting call by idempotency key, so a resumed run never repeats it (§7.2). */
@Entity(tableName = "tool_results")
data class ToolResultEntity(
    @PrimaryKey val idempotencyKey: String,
    val resultJson: String,
    val atMillis: Long,
)

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY seq")
    suspend fun load(conversationId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(seq) + 1, 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun nextSeq(conversationId: String): Int

    @Insert
    suspend fun insert(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND seq >= :fromSeq")
    suspend fun deleteFrom(conversationId: String, fromSeq: Int)
}

@Dao
interface LedgerDao {
    @Insert
    suspend fun insert(entry: LedgerEntity)

    @Query("SELECT * FROM ledger ORDER BY atMillis, id")
    fun all(): Flow<List<LedgerEntity>>
}

@Dao
interface VaultAuditDao {
    /** Blocking: the vault reports uses from OkHttp's thread. */
    @Insert
    fun insertBlocking(entry: VaultAuditEntity)

    @Query("SELECT * FROM vault_audit ORDER BY atMillis DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<VaultAuditEntity>>
}

@Dao
interface ToolAuditDao {
    @Insert
    suspend fun insert(entry: ToolAuditEntity)

    @Query("SELECT * FROM tool_audit ORDER BY atMillis DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ToolAuditEntity>>
}

@Dao
interface ToolResultDao {
    @Query("SELECT resultJson FROM tool_results WHERE idempotencyKey = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun put(entry: ToolResultEntity)
}

/**
 * Anu's database (ARCHITECTURE §14): Room on SQLCipher, encrypted with a key wrapped by Android
 * Keystore (ADR-024). It lives in `noBackupFilesDir`, and backup is disabled for the whole app.
 */
@Database(
    entities = [
        MessageEntity::class, LedgerEntity::class, VaultAuditEntity::class, ToolAuditEntity::class, ToolResultEntity::class,
        MemoryEventEntity::class, FactEntity::class, FactSourceEntity::class, RejectedFactEntity::class, ProfileVersionEntity::class,
        McpServerEntity::class, StoreEntity::class, FactVectorEntity::class, AnswerEntity::class, PhotoEntity::class,
    ],
    version = 9,
    // v2 (Phase 2) adds tool_audit and tool_results; v3 (Phase 3) adds memory; v4 adds MCP servers and
    // stores (ADR-046, ADR-047); v5 adds fact vectors for search by meaning (ADR-055); v6 adds answers'
    // details (ADR-071); v7 adds photos and the messages' photo ids (ADR-075); v8 adds the messages' voice notes
    // (ADR-078); v9 adds the screen a message asked about (ADR-083). Room derives each migration from the committed
    // schemas.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ],
)
abstract class AnuDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao

    abstract fun ledger(): LedgerDao

    abstract fun vaultAudit(): VaultAuditDao

    abstract fun toolAudit(): ToolAuditDao

    abstract fun toolResults(): ToolResultDao

    abstract fun memory(): MemoryDao

    abstract fun connectors(): ConnectorDao

    abstract fun answers(): AnswerDao

    abstract fun photos(): PhotoDao

    companion object {
        fun open(context: Context): AnuDatabase {
            System.loadLibrary("sqlcipher")
            val dir = File(context.noBackupFilesDir, "db")
            val key = DatabaseKey(File(dir, "key.bin"), KeystoreAead("anu.data.db", KeyPolicy.AfterFirstUnlock))
            return Room.databaseBuilder(context, AnuDatabase::class.java, File(dir, "anu.db").absolutePath)
                .openHelperFactory(SupportOpenHelperFactory(key.sqlcipherKey()))
                .build()
        }
    }
}
