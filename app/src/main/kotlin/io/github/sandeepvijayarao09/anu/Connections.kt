package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.connector.mcp.McpRegistry
import io.github.sandeepvijayarao09.anu.connector.mcp.McpServer
import io.github.sandeepvijayarao09.anu.connector.mcp.McpTool
import io.github.sandeepvijayarao09.anu.connector.shopping.Store
import io.github.sandeepvijayarao09.anu.connector.shopping.UcpClient
import io.github.sandeepvijayarao09.anu.core.data.McpServerEntity
import io.github.sandeepvijayarao09.anu.core.data.RoomConnectorSettings
import io.github.sandeepvijayarao09.anu.core.data.StoreEntity
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.vault.ItemType
import io.github.sandeepvijayarao09.anu.core.vault.NewVaultItem
import io.github.sandeepvijayarao09.anu.core.vault.Protection
import io.github.sandeepvijayarao09.anu.core.vault.Vault
import io.github.sandeepvijayarao09.anu.feature.settings.McpServerRow
import io.github.sandeepvijayarao09.anu.feature.settings.McpServerSettings
import io.github.sandeepvijayarao09.anu.feature.settings.StoreRow
import io.github.sandeepvijayarao09.anu.feature.settings.StoreSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * The user's MCP servers (ADR-046): saved in the database, tokens in the vault bound to the server's
 * host, and tools in the [registry].
 */
class McpConnections(
    private val settings: RoomConnectorSettings,
    private val vault: Vault,
    val registry: McpRegistry,
    scope: CoroutineScope,
    /** Plain HTTP only reaches the Mac's test servers, in debug builds. */
    private val allowPlainHttp: (HttpUrl) -> Boolean,
) : McpServerSettings {

    override val servers: StateFlow<List<McpServerRow>> = registry.servers
        .map { list -> list.map(::row) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun row(state: McpRegistry.ServerState) = McpServerRow(
        state.server.id,
        state.server.name,
        state.server.host,
        state.tools.map { it.info.name },
        state.error,
        state.server.token != null,
        loaded = state.loadedAt != null || state.error != null,
    )

    /** Keeps the registry in step with the saved servers, for the app's lifetime. */
    suspend fun sync() = settings.mcpServers().collect { saved ->
        registry.setServers(saved.mapNotNull { entity ->
            val url = entity.url.toHttpUrlOrNull() ?: return@mapNotNull null
            McpServer(entity.id, entity.name, entity.url, url.host, entity.tokenRef?.let(::SecretRef))
        })
    }

    override suspend fun add(name: String, url: String, token: String?): McpServerRow {
        require(name.isNotBlank()) { "Give the server a name." }
        require(registry.servers.value.none { it.server.name.equals(name, ignoreCase = true) }) { "There's already a server called $name." }
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("\"$url\" isn't a web address.")
        require(parsed.isHttps || allowPlainHttp(parsed)) { "The server must use https." }
        require(token == null || parsed.isHttps) { "A token is only ever sent over https." }
        val id = UUID.randomUUID().toString()
        val ref = token?.let {
            vault.put(
                NewVaultItem(
                    type = ItemType.API_KEY,
                    label = "MCP token: $name",
                    service = "$name (MCP)",
                    secret = it,
                    boundHosts = setOf(parsed.host),
                    allowedUsers = setOf(McpTool.CALLER),
                    protection = Protection.DEVICE_UNLOCKED,
                ),
            ).id
        }
        settings.saveMcpServer(McpServerEntity(id, name.trim(), parsed.toString(), ref, System.currentTimeMillis()))
        // The registry hears of the server from the database. Then its tools are fetched.
        withTimeout(10_000) { registry.servers.first { list -> list.any { it.server.id == id } } }
        return row(registry.refresh(id))
    }

    override suspend fun refresh(id: String) {
        registry.refresh(id)
    }

    override suspend fun loadMissing() {
        registry.servers.value.filter { it.loadedAt == null && it.error == null }.forEach { registry.refresh(it.server.id) }
    }

    override suspend fun remove(id: String) {
        val saved = settings.mcpServers().first().firstOrNull { it.id == id } ?: return
        saved.tokenRef?.let { vault.delete(SecretRef(it)) }
        settings.deleteMcpServer(id)
    }
}

/** The user's stores (ADR-047), saved as their UCP profiles described them. */
class StoreConnections(
    private val settings: RoomConnectorSettings,
    private val client: UcpClient,
    scope: CoroutineScope,
) : StoreSettings {
    private val current = MutableStateFlow<List<Store>>(emptyList())

    /** For the shopping tools. */
    fun all(): List<Store> = current.value

    override val stores: StateFlow<List<StoreRow>> = current
        .map { list -> list.map(::row) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Keeps [all] in step with the saved stores, for the app's lifetime. */
    suspend fun sync() = settings.stores().collect { saved ->
        current.value = saved.map { Store(it.address, it.name, it.endpoint, it.capabilities.lines().filter(String::isNotBlank).toSet(), it.version) }
    }

    override suspend fun add(address: String): StoreRow {
        val store = client.discover(address)
        require(current.value.none { it.address == store.address }) { "${store.name} is already added." }
        settings.saveStore(StoreEntity(store.address, store.name, store.endpoint, store.capabilities.joinToString("\n"), store.version, System.currentTimeMillis()))
        return row(store)
    }

    override suspend fun remove(address: String) = settings.deleteStore(address)

    private fun row(store: Store) = StoreRow(store.address, store.name, store.endpointHost, store.canSearch, store.canCheckout)
}
