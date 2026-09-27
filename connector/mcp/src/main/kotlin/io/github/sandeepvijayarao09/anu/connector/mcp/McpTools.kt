package io.github.sandeepvijayarao09.anu.connector.mcp

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/** An MCP server the user added in Settings (ADR-046). Its token, if any, is in the vault: [token] is only a handle. */
data class McpServer(val id: String, val name: String, val url: String, val host: String, val token: SecretRef? = null)

/**
 * One tool of an MCP server, offered to the model as `mcp_<server>_<tool>` (ADR-046).
 * - **Risk comes from the server's annotations,** which the server wrote: `readOnlyHint` makes it READ;
 *   otherwise it needs approval, as `DESTRUCTIVE` unless `destructiveHint` is false.
 * - **Everything it returns is untrusted** and taints the run (P4).
 * - **Every call sends its arguments to the server,** so each one writes a ledger entry (I3), and once
 *   the run is tainted even a READ call needs approval ([sendsTo]).
 * - The arguments are checked against the server's schema before approval (I6), and sent exactly as
 *   approved (I2).
 */
class McpTool(
    val server: McpServer,
    val info: McpToolInfo,
    override val name: String,
    private val client: McpClient,
    private val ledger: PrivacyLedger?,
    private val clock: () -> Instant = Instant::now,
) : Tool<JsonObject, JsonObject> {
    private val label = McpText.clean(info.title ?: info.name, 60)

    override val description: String =
        "${McpText.clean(server.name, 40)} (a service the user added): " + McpText.clean(info.description.ifBlank { label }, MAX_DESCRIPTION)
    override val input: KSerializer<JsonObject> = JsonObject.serializer()
    override val output: KSerializer<JsonObject> = JsonObject.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = true

    private val parameters = McpSchemas.forModel(info.inputSchema)

    override fun spec(): ToolSpec = ToolSpec(name, description, parameters)

    override fun risk(input: JsonObject): RiskTier {
        McpSchemas.validate(info.inputSchema, input)
        return when {
            info.readOnly -> RiskTier.READ
            info.destructive -> RiskTier.DESTRUCTIVE
            else -> RiskTier.WRITE_EXTERNAL
        }
    }

    override fun describe(input: JsonObject): String {
        val arguments = input.entries.joinToString("; ") { (key, value) ->
            "$key: " + McpText.clean((value as? JsonPrimitive)?.contentOrNull ?: value.toString(), 80)
        }
        return "Send to ${McpText.clean(server.name, 40)} (${server.host}): $label" + if (arguments.isEmpty()) "" else " · $arguments"
    }

    override fun sendsTo(input: JsonObject): String = server.host

    override val editable: List<EditableField> = McpSchemas.editable(info.inputSchema)

    override suspend fun invoke(input: JsonObject, context: ToolContext): JsonObject {
        ledger?.record(LedgerEntry(clock(), server.host, "MCP ${server.name}: ${info.name}", dataClasses, emptyMap(), input.toString().length))
        val result = client.callTool(info, input)
        if (result.isError) throw McpException("${server.name} said: ${McpText.clean(result.text, 300)}")
        return buildJsonObject {
            put("service", server.name)
            put("text", if (result.text.length > MAX_RESULT) result.text.take(MAX_RESULT) + "… (cut)" else result.text)
            result.structured?.takeIf { it.toString().length <= MAX_RESULT }?.let { put("structured", it) }
        }
    }

    companion object {
        /** Who asks the vault for a server's token (§11). */
        const val CALLER = "connector:mcp"
        private const val MAX_DESCRIPTION = 300

        /** Results share the on-device context with everything else (ADR-040). */
        const val MAX_RESULT = 2_000
    }
}

/**
 * The user's MCP servers and their tools (ADR-046). Tools are fetched when a server is added or first
 * needed, kept for the server's `ttlMs` (at most a day), and offered only in turns where the user names
 * the server, a few at a time.
 */
class McpRegistry(
    private val clientFor: (McpServer) -> McpClient,
    private val ledger: PrivacyLedger?,
    private val clock: () -> Instant = Instant::now,
) {
    data class ServerState(
        val server: McpServer,
        val tools: List<McpTool> = emptyList(),
        val rejected: List<String> = emptyList(),
        val error: String? = null,
        val loadedAt: Instant? = null,
        val expiresAt: Instant? = null,
    )

    private val state = MutableStateFlow<List<ServerState>>(emptyList())
    val servers: StateFlow<List<ServerState>> = state.asStateFlow()
    private val clients = mutableMapOf<String, McpClient>()

    /** The saved servers. Tools already fetched are kept for servers that didn't change. */
    fun setServers(servers: List<McpServer>) = state.update { current ->
        val byId = current.associateBy { it.server.id }
        servers.map { server -> byId[server.id]?.takeIf { it.server == server } ?: ServerState(server) }
    }.also {
        synchronized(clients) { clients.keys.retainAll(servers.map { it.id }.toSet()) }
    }

    /** Every fetched tool, for the Tool Broker. */
    fun tools(): List<McpTool> = state.value.flatMap { it.tools }

    /** Fetches [id]'s tools now. Failures are kept on the server's state, for Settings to show. */
    suspend fun refresh(id: String): ServerState {
        val server = state.value.firstOrNull { it.server.id == id }?.server ?: throw IllegalArgumentException("No server $id")
        val client = synchronized(clients) { clients.getOrPut(id) { clientFor(server) } }
        val updated = try {
            val list = client.listTools()
            val now = clock()
            val ttl = (list.ttlMillis ?: DEFAULT_TTL_MILLIS).coerceIn(0, MAX_TTL_MILLIS)
            ServerState(server, name(server, list.tools, client), list.rejected, null, now, now.plusMillis(ttl))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerState(server, state.value.firstOrNull { it.server.id == id }?.tools.orEmpty(), error = e.message ?: "The server couldn't be reached.")
        }
        state.update { list -> list.map { if (it.server.id == id) updated else it } }
        return updated
    }

    /**
     * The tools to offer for a turn: those of servers named in the user's last few messages, fetched
     * if they aren't yet, and at most [MAX_OFFERED], best matches first.
     */
    suspend fun toolsFor(turns: List<String>): List<String> {
        val recent = turns.takeLast(RECENT_TURNS).joinToString("\n")
        val named = state.value.filter { mentioned(it.server.name, recent) }
        if (named.isEmpty()) return emptyList()
        val now = clock()
        val ready = named.map { entry -> if (entry.expiresAt == null || now.isAfter(entry.expiresAt)) refresh(entry.server.id) else entry }
        val words = words(turns.lastOrNull().orEmpty())
        return ready.flatMap { it.tools }
            .sortedByDescending { tool -> words(tool.info.name.replace('_', ' ') + " " + tool.info.description).count { it in words } }
            .take(MAX_OFFERED)
            .map { it.name }
    }

    private fun name(server: McpServer, tools: List<McpToolInfo>, client: McpClient): List<McpTool> {
        val taken = state.value.filter { it.server.id != server.id }.flatMap { it.tools.map(McpTool::name) }.toMutableSet()
        val prefix = "mcp_" + slug(server.name, 16).ifEmpty { "server" } + "_"
        return tools.map { info ->
            val base = (prefix + slug(info.name, 64 - prefix.length).ifEmpty { "tool" }).take(64)
            var name = base
            var n = 2
            while (!taken.add(name)) name = base.take(60) + "_" + n++
            McpTool(server, info, name, client, ledger, clock)
        }
    }

    companion object {
        const val MAX_OFFERED = 6
        private const val RECENT_TURNS = 3
        private const val DEFAULT_TTL_MILLIS = 60 * 60_000L
        private const val MAX_TTL_MILLIS = 24 * 60 * 60_000L
        private val NON_WORD = Regex("[^a-z0-9]+")

        /** snake_case, as tool names must be: "My Notes!" becomes "my_notes". */
        fun slug(text: String, max: Int): String {
            val plain = NON_WORD.replace(text.lowercase(), "_").trim('_')
            val start = plain.dropWhile { !it.isLetter() }
            return start.take(max).trimEnd('_')
        }

        /** The server's name as whole words, in any case: "notes" names "Notes", "notebook" doesn't. */
        fun mentioned(name: String, text: String): Boolean {
            val words = name.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString("\\s+") { Regex.escape(it) }
            return words.isNotEmpty() && Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        private fun words(text: String): Set<String> = NON_WORD.split(text.lowercase()).filter { it.length > 2 }.toSet()
    }
}
