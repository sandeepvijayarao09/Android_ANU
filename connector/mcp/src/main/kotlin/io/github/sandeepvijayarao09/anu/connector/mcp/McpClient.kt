package io.github.sandeepvijayarao09.anu.connector.mcp

import io.github.sandeepvijayarao09.anu.core.security.SecretUse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A tool as its MCP server describes it. The server wrote all of it, so none of it is trusted. */
data class McpToolInfo(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: JsonObject,
    /** `readOnlyHint`, false when absent. */
    val readOnly: Boolean = false,
    /** `destructiveHint`, true when absent, as MCP defines it. */
    val destructive: Boolean = true,
)

/** [rejected] names tools left out because their definitions break MCP's rules, with the reason. */
data class McpToolList(val tools: List<McpToolInfo>, val rejected: List<String> = emptyList(), val ttlMillis: Long? = null)

data class McpCallResult(val text: String, val structured: JsonElement?, val isError: Boolean)

class McpException(message: String, val code: Int? = null) : IOException(message)

/**
 * A client for one MCP server over Streamable HTTP (ADR-046). It is dual-era:
 * - It speaks the stateless 2026-07-28 revision first: every request carries its version, client info
 *   and capabilities in `_meta`, mirrored into `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name`.
 * - A 4xx whose body isn't a recognized modern MCP error means an older server. The client then opens a
 *   2025 `initialize` session, with `Mcp-Session-Id`, and keeps using it for this server.
 * - Replies may be JSON or a request-scoped SSE stream. Notifications on the stream are skipped.
 * - Anu declares no client capabilities, so it never serves sampling or elicitation. An
 *   `input_required` result is reported as an error.
 * - Cancelling the coroutine cancels the HTTP call (I7).
 */
class McpClient(
    private val http: OkHttpClient,
    private val endpoint: HttpUrl,
    private val clientVersion: String,
    /** The vault attaches the server's token to requests for its bound host (§11). */
    private val auth: SecretUse? = null,
) {
    private enum class Era { MODERN, LEGACY }

    private data class Session(val version: String, val id: String?)

    private sealed interface Reply {
        val sessionId: String?

        data class Ok(val result: JsonObject, override val sessionId: String?) : Reply

        data class RpcError(val code: Int?, val message: String, val data: JsonElement?, override val sessionId: String?) : Reply

        data class Http(val status: Int, val error: RpcError?, override val sessionId: String?) : Reply
    }

    private val ids = AtomicLong(0)

    @Volatile
    private var era: Era? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var legacyVersion = LEGACY_VERSIONS.first()
    private val sessionLock = Mutex()

    /** Every tool the server offers, following pages. Invalid definitions are left out, as MCP requires. */
    suspend fun listTools(): McpToolList {
        val tools = mutableListOf<McpToolInfo>()
        val rejected = mutableListOf<String>()
        var ttl: Long? = null
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            ttl = result["ttlMs"]?.jsonPrimitive?.longOrNull ?: ttl
            result["tools"]?.jsonArray.orEmpty().forEach { element ->
                val tool = element as? JsonObject ?: return@forEach
                val name = tool["name"]?.jsonPrimitive?.contentOrNull
                if (name.isNullOrBlank()) {
                    rejected += "a tool without a name"
                    return@forEach
                }
                val schema = tool["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") }
                val problem = McpSchemas.headerProblem(schema)
                if (problem != null) {
                    rejected += "$name: $problem"
                    return@forEach
                }
                val annotations = tool["annotations"] as? JsonObject
                tools += McpToolInfo(
                    name = name,
                    title = (tool["title"] ?: annotations?.get("title"))?.jsonPrimitive?.contentOrNull,
                    description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    inputSchema = schema,
                    readOnly = annotations?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull ?: false,
                    destructive = annotations?.get("destructiveHint")?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull ?: return McpToolList(tools, rejected, ttl)
        }
        return McpToolList(tools, rejected, ttl)
    }

    /** Calls [tool] with [arguments], exactly as approved. Parameters marked `x-mcp-header` are mirrored into headers. */
    suspend fun callTool(tool: McpToolInfo, arguments: JsonObject): McpCallResult {
        val params = buildJsonObject {
            put("name", tool.name)
            put("arguments", arguments)
        }
        val result = request("tools/call", params, name = tool.name, headers = McpSchemas.paramHeaders(tool.inputSchema, arguments))
        if (result["resultType"]?.jsonPrimitive?.contentOrNull == "input_required") {
            throw McpException("The server asked for more input, which Anu can't give yet.")
        }
        val parts = result["content"]?.jsonArray.orEmpty().mapNotNull { element ->
            val part = element as? JsonObject ?: return@mapNotNull null
            when (part["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> part["text"]?.jsonPrimitive?.contentOrNull
                "image" -> "[image]"
                "audio" -> "[audio]"
                "resource_link" -> "[link: ${part["name"]?.jsonPrimitive?.contentOrNull.orEmpty()} ${part["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()}]"
                "resource" -> (part["resource"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: "[resource]"
                else -> null
            }
        }
        return McpCallResult(
            text = parts.joinToString("\n"),
            structured = result["structuredContent"],
            isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private suspend fun request(method: String, params: JsonObject, name: String? = null, headers: Map<String, String> = emptyMap()): JsonObject {
        if (era == Era.LEGACY) return legacy(method, params)
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", JsonObject(params + ("_meta" to meta())))
        }
        val request = post(body)
            .header("MCP-Protocol-Version", MODERN_VERSION)
            .header("Mcp-Method", method)
            .apply {
                name?.let { header("Mcp-Name", McpSchemas.headerValue(it)) }
                headers.forEach { (key, value) -> header(key, value) }
            }
            .build()
        return when (val reply = send(request, id)) {
            is Reply.Ok -> reply.result.also { era = Era.MODERN }
            is Reply.RpcError -> {
                era = Era.MODERN
                throw McpException(reply.message, reply.code)
            }
            is Reply.Http -> {
                val error = reply.error
                val supported = (error?.data as? JsonObject)?.get("supported")?.let { runCatching { it.jsonArray.mapNotNull { v -> v.jsonPrimitive.contentOrNull } }.getOrNull() }
                when {
                    // A modern server that doesn't serve this revision may still serve a 2025 one.
                    error?.code == UNSUPPORTED_VERSION && supported != null -> {
                        val fallback = LEGACY_VERSIONS.firstOrNull { it in supported }
                            ?: throw McpException("The server speaks MCP ${supported.joinToString()}, which Anu doesn't.", error.code)
                        legacyVersion = fallback
                        era = Era.LEGACY
                        legacy(method, params)
                    }
                    error?.code == HEADER_MISMATCH -> {
                        era = Era.MODERN
                        throw McpException(error.message, error.code)
                    }
                    reply.status == 404 && error?.code == METHOD_NOT_FOUND -> {
                        era = Era.MODERN
                        throw McpException("The server doesn't offer $method.", error.code)
                    }
                    reply.status in LEGACY_STATUSES && era == null -> {
                        era = Era.LEGACY
                        legacy(method, params)
                    }
                    else -> throw httpError(reply)
                }
            }
        }
    }

    private suspend fun legacy(method: String, params: JsonObject): JsonObject {
        val current = session ?: initialize()
        val reply = legacySend(method, params, current)
        // An expired session answers 404: start a new one, once.
        if (reply is Reply.Http && reply.status == 404 && current.id != null) {
            sessionLock.withLock { if (session == current) session = null }
            return result(legacySend(method, params, initialize()))
        }
        return result(reply)
    }

    private suspend fun legacySend(method: String, params: JsonObject, session: Session): Reply {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return send(post(body).header("MCP-Protocol-Version", session.version).sessionHeader(session.id).build(), id)
    }

    private suspend fun initialize(): Session = sessionLock.withLock {
        session?.let { return it }
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", legacyVersion)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", CLIENT_NAME)
                    put("version", clientVersion)
                }
            }
        }
        val reply = send(post(body).build(), id)
        val version = result(reply)["protocolVersion"]?.jsonPrimitive?.contentOrNull
        if (version !in LEGACY_VERSIONS) throw McpException("The server speaks MCP $version, which Anu doesn't.")
        val opened = Session(version!!, reply.sessionId)
        val initialized = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        call(post(initialized).header("MCP-Protocol-Version", opened.version).sessionHeader(opened.id).build()) { response ->
            if (!response.isSuccessful) throw McpException("The server refused to start a session (HTTP ${response.code}).")
        }
        opened.also { session = it }
    }

    private fun result(reply: Reply): JsonObject = when (reply) {
        is Reply.Ok -> reply.result
        is Reply.RpcError -> throw McpException(reply.message, reply.code)
        is Reply.Http -> throw httpError(reply)
    }

    private fun httpError(reply: Reply.Http): McpException = when (reply.status) {
        401 -> McpException("The server needs sign-in. Check its token in Settings.", reply.error?.code)
        403 -> McpException("The server refused the request.", reply.error?.code)
        else -> McpException(reply.error?.message ?: "The server answered HTTP ${reply.status}.", reply.error?.code)
    }

    private fun meta() = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", MODERN_VERSION)
        putJsonObject("io.modelcontextprotocol/clientInfo") {
            put("name", CLIENT_NAME)
            put("version", clientVersion)
        }
        putJsonObject("io.modelcontextprotocol/clientCapabilities") {}
    }

    private fun post(body: JsonObject): Request.Builder = Request.Builder()
        .url(endpoint)
        .header("Accept", "application/json, text/event-stream")
        .post(body.toString().toRequestBody(JSON))
        .apply { auth?.let { tag(SecretUse::class.java, it) } }

    private fun Request.Builder.sessionHeader(id: String?) = apply { id?.let { header("Mcp-Session-Id", it) } }

    private suspend fun send(request: Request, id: Long): Reply = call(request) { response -> parse(response, id) }

    /** Runs [request] and hands the response to [handle] on OkHttp's thread. Cancelling cancels the call. */
    private suspend fun <T> call(request: Request, handle: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = runCatching { response.use(handle) }
                outcome.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private fun parse(response: Response, id: Long): Reply {
        val sessionId = response.header("Mcp-Session-Id")
        if (!response.isSuccessful) {
            val text = response.body.source().readLimited()
            return Reply.Http(response.code, runCatching { rpcError(json.parseToJsonElement(text).jsonObject, sessionId) }.getOrNull(), sessionId)
        }
        val message = if (response.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
            Sse.response(response.body.source(), id)
        } else {
            json.parseToJsonElement(response.body.source().readLimited()).jsonObject
        }
        rpcError(message, sessionId)?.let { return it }
        val result = message["result"] as? JsonObject ?: throw McpException("The server's reply had no result.")
        return Reply.Ok(result, sessionId)
    }

    private fun rpcError(message: JsonObject, sessionId: String?): Reply.RpcError? {
        val error = message["error"] as? JsonObject ?: return null
        return Reply.RpcError(
            code = error["code"]?.jsonPrimitive?.intOrNull,
            message = error["message"]?.jsonPrimitive?.contentOrNull ?: "The server reported an error.",
            data = error["data"],
            sessionId = sessionId,
        )
    }

    private fun BufferedSource.readLimited(): String {
        request(MAX_BYTES + 1)
        if (buffer.size > MAX_BYTES) throw McpException("The server's reply is too large.")
        return buffer.readUtf8()
    }

    companion object {
        const val MODERN_VERSION = "2026-07-28"

        /** The initialization-based revisions Anu falls back to, newest first. */
        val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        const val CLIENT_NAME = "Anu"

        private const val UNSUPPORTED_VERSION = -32022
        private const val HEADER_MISMATCH = -32020
        private const val METHOD_NOT_FOUND = -32601
        private val LEGACY_STATUSES = setOf(400, 404, 405)
        private const val MAX_PAGES = 10
        private const val MAX_BYTES = 2L * 1024 * 1024
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Reads a request-scoped SSE stream up to the JSON-RPC response for one request id. */
internal object Sse {
    private val json = Json { ignoreUnknownKeys = true }

    fun response(source: BufferedSource, id: Long): JsonObject {
        val data = StringBuilder()
        var event: String? = null
        fun take(): JsonObject? {
            val message = if (data.isNotEmpty() && (event == null || event == "message")) {
                runCatching { json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
            } else {
                null
            }
            data.clear()
            event = null
            val matches = message?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull == id.toString() } == true
            return message?.takeIf { matches && ("result" in it || "error" in it) }
        }
        var read = 0L
        while (true) {
            val line = source.readUtf8Line() ?: break
            read += line.length
            if (read > 2L * 1024 * 1024) throw McpException("The server's reply is too large.")
            when {
                line.isEmpty() -> take()?.let { return it }
                line.startsWith(":") -> Unit // a comment, such as a keep-alive
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                else -> Unit // id: and retry: aren't used: streams can't be resumed
            }
        }
        take()?.let { return it }
        throw McpException("The server closed the stream without answering.")
    }
}
