package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** An MCP server the user added (ADR-046). Its token lives in the vault; only the handle is here. */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val tokenRef: String?,
    val addedAtMillis: Long,
)

/** A store the user added (ADR-047), as its UCP profile described it. */
@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey val address: String,
    val name: String,
    val endpoint: String,
    /** Capability names, one per line. */
    val capabilities: String,
    val version: String,
    val addedAtMillis: Long,
)

@Dao
interface ConnectorDao {
    @Query("SELECT * FROM mcp_servers ORDER BY addedAtMillis")
    fun mcpServers(): Flow<List<McpServerEntity>>

    @Upsert
    suspend fun saveMcpServer(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteMcpServer(id: String)

    @Query("SELECT * FROM stores ORDER BY addedAtMillis")
    fun stores(): Flow<List<StoreEntity>>

    @Upsert
    suspend fun saveStore(store: StoreEntity)

    @Query("DELETE FROM stores WHERE address = :address")
    suspend fun deleteStore(address: String)
}

/** The services and stores the user connected in Settings (§9). */
class RoomConnectorSettings(private val dao: ConnectorDao) {
    fun mcpServers(): Flow<List<McpServerEntity>> = dao.mcpServers()

    suspend fun saveMcpServer(server: McpServerEntity) = dao.saveMcpServer(server)

    suspend fun deleteMcpServer(id: String) = dao.deleteMcpServer(id)

    fun stores(): Flow<List<StoreEntity>> = dao.stores()

    suspend fun saveStore(store: StoreEntity) = dao.saveStore(store)

    suspend fun deleteStore(address: String) = dao.deleteStore(address)
}
