package io.github.sandeepvijayarao09.anu.connector.a2a

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An agent the user added, as its card describes it (ADR-105): where it answers, and what it says it can do. */
data class A2aAgent(val name: String, val description: String, val url: String, val skills: List<String>) {
    val host: String get() = url.toHttpUrl().host
}

class A2aException(message: String) : Exception(message)

/**
 * The Agent2Agent protocol's client side, over JSON-RPC (ADR-105): an agent's card from its well-known address, and
 * `message/send` with the user's text. Anu is only ever the client: nothing on the network can call into the phone.
 */
class A2aClient(private val http: OkHttpClient) {
    /** The agent at [base], from `/.well-known/agent-card.json`, or the older `agent.json`. */
    suspend fun card(base: HttpUrl): A2aAgent {
        val card = CARD_PATHS.firstNotNullOfOrNull { path ->
            runCatching { get(base.resolve(path) ?: return@firstNotNullOfOrNull null) }.getOrNull()
        } ?: throw A2aException("No agent card at $base")
        val name = (card["name"] as? JsonPrimitive)?.contentOrNull?.let { A2aText.clean(it, 40) }?.takeIf { it.isNotBlank() }
            ?: throw A2aException("The agent card at $base has no name")
        // An agent names where it answers; a relative or missing url means the address it was added with.
        val url = (card["url"] as? JsonPrimitive)?.contentOrNull?.let { base.resolve(it) } ?: base
        val skills = (card["skills"] as? JsonArray).orEmpty().mapNotNull { skill ->
            val obj = skill as? JsonObject ?: return@mapNotNull null
            ((obj["name"] ?: obj["id"]) as? JsonPrimitive)?.contentOrNull?.let { A2aText.clean(it, 40) }
        }.take(MAX_SKILLS)
        val description = A2aText.clean((card["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), MAX_DESCRIPTION)
        return A2aAgent(name, description, url.toString(), skills)
    }

    /** Sends [text] to [agent] and returns the text of its answer: a message, or a task's status message and artifacts. */
    suspend fun send(agent: A2aAgent, text: String): String {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", "message/send")
            putJsonObject("params") {
                putJsonObject("message") {
                    put("kind", "message")
                    put("role", "user")
                    put("messageId", UUID.randomUUID().toString())
                    putJsonArray("parts") { addJsonObject { put("kind", "text"); put("text", text) } }
                }
            }
        }
        val request = Request.Builder().url(agent.url).post(body.toString().toRequestBody(JSON_TYPE)).build()
        val reply = call(request) { response ->
            if (!response.isSuccessful) throw A2aException("${agent.name} answered HTTP ${response.code}")
            json.parseToJsonElement(response.body.string().take(MAX_BYTES)).jsonObject
        }
        (reply["error"] as? JsonObject)?.let { error ->
            throw A2aException("${agent.name} said: " + A2aText.clean((error["message"] as? JsonPrimitive)?.contentOrNull ?: "an error", 200))
        }
        val result = reply["result"] as? JsonObject ?: throw A2aException("${agent.name}'s reply had no result")
        val texts = texts(result["parts"]) + texts((result["status"] as? JsonObject)?.get("message")?.let { (it as? JsonObject)?.get("parts") }) +
            (result["artifacts"] as? JsonArray).orEmpty().flatMap { texts((it as? JsonObject)?.get("parts")) }
        return A2aText.clean(texts.joinToString("\n"), MAX_ANSWER).ifBlank { throw A2aException("${agent.name} answered with no text") }
    }

    private fun texts(parts: JsonElement?): List<String> = (parts as? JsonArray).orEmpty().mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        if ((obj["kind"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != "text" }) return@mapNotNull null
        (obj["text"] as? JsonPrimitive)?.contentOrNull
    }

    private suspend fun get(url: HttpUrl): JsonObject = call(Request.Builder().url(url).get().build()) { response ->
        if (!response.isSuccessful) throw A2aException("HTTP ${response.code}")
        json.parseToJsonElement(response.body.string().take(MAX_BYTES)).jsonObject
    }

    /** Runs [request] and hands the response to [handle] on OkHttp's thread. Cancelling cancels the call (I7). */
    private suspend fun <T> call(request: Request, handle: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                runCatching { response.use(handle) }.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    companion object {
        private val CARD_PATHS = listOf("/.well-known/agent-card.json", "/.well-known/agent.json")
        private val JSON_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_BYTES = 200_000
        private const val MAX_SKILLS = 8
        private const val MAX_DESCRIPTION = 200

        /** Answers share the on-device context with everything else (ADR-040). */
        const val MAX_ANSWER = 2_000
    }
}

/** Text from another device, made safe to show a model: no control characters, one line per line, and short. */
internal object A2aText {
    fun clean(text: String, max: Int): String {
        val plain = text.filter { it == '\n' || !it.isISOControl() }.trim()
        return if (plain.length > max) plain.take(max).trimEnd() + "…" else plain
    }
}

@Serializable
data class AskAgentInput(@Description("What to ask or tell the agent, in the user's words") val message: String)

@Serializable
data class AgentAnswer(val agent: String, val text: String)

/**
 * An agent on the user's network as a tool, `a2a_<name>` (ADR-105). Its message leaves the phone for another device,
 * so it's `WRITE_EXTERNAL`: the user approves the exact words every time, and may edit them (I2); each send writes a
 * ledger entry (I3). What the agent answers was written by another program, so it taints the run (P4).
 */
class A2aTool(
    val agent: A2aAgent,
    private val client: A2aClient,
    private val ledger: PrivacyLedger?,
    private val clock: () -> Instant = Instant::now,
) : Tool<AskAgentInput, AgentAnswer> {
    override val name: String = toolName(agent.name)
    override val description: String =
        "${agent.name}, an agent the user added on their own network" +
            (if (agent.description.isNotBlank()) ": ${agent.description}" else "") +
            (if (agent.skills.isNotEmpty()) ". It can: ${agent.skills.joinToString(", ")}" else "")
    override val input: KSerializer<AskAgentInput> = AskAgentInput.serializer()
    override val output: KSerializer<AgentAnswer> = AgentAnswer.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent = UntrustedContent.SERVICE
    override val editable = listOf(EditableField("message", "Message", EditableField.Kind.TEXT))

    override fun risk(input: AskAgentInput) = RiskTier.WRITE_EXTERNAL

    override fun sendsTo(input: AskAgentInput): String = agent.host

    override fun describe(input: AskAgentInput): String {
        require(input.message.isNotBlank()) { "the message is empty" }
        require(input.message.length <= MAX_MESSAGE) { "the message is longer than $MAX_MESSAGE characters" }
        return "Ask ${agent.name} (${agent.host}): “${input.message.trim()}”"
    }

    override suspend fun invoke(input: AskAgentInput, context: ToolContext): AgentAnswer {
        ledger?.record(LedgerEntry(clock(), agent.host, "Agent ${agent.name}", dataClasses, emptyMap(), input.message.length))
        return AgentAnswer(agent.name, client.send(agent, input.message.trim()))
    }

    companion object {
        private const val MAX_MESSAGE = 1_000

        /** "Home Lab" → `a2a_home_lab`, as tool names are snake_case (§7.1). */
        fun toolName(agentName: String): String =
            "a2a_" + agentName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(50).ifEmpty { "agent" }
    }
}

/** Which agents the user added, by address (ADR-105). The app keeps it; tests use a list. */
interface A2aAgentList {
    fun addresses(): List<String>

    fun add(address: String)

    fun remove(address: String)
}

/**
 * The agents the user added, with their cards (ADR-105). [refresh] fetches each card once; an agent that doesn't answer
 * is left out until the next refresh, and says why in [problems].
 */
class A2aAgents(private val list: A2aAgentList, private val client: A2aClient, private val ledger: PrivacyLedger?) {
    @Volatile
    private var current: List<A2aTool> = emptyList()

    @Volatile
    var problems: Map<String, String> = emptyMap()
        private set

    fun tools(): List<A2aTool> = current

    suspend fun refresh() {
        val found = mutableListOf<A2aTool>()
        val failed = mutableMapOf<String, String>()
        for (address in list.addresses()) {
            try {
                found += A2aTool(client.card(address.toHttpUrl()), client, ledger)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[address] = e.message ?: e::class.simpleName.orEmpty()
            }
        }
        current = found.distinctBy { it.name }
        problems = failed
    }

    /** Only http(s) addresses; anything else can't be an agent. */
    fun add(address: String): Boolean {
        val url = runCatching { address.trim().toHttpUrl() }.getOrNull() ?: return false
        list.add(url.toString())
        return true
    }

    fun remove(address: String) = list.remove(address.trim())
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
