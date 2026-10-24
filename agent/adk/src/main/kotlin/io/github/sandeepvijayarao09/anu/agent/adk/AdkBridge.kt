package io.github.sandeepvijayarao09.anu.agent.adk

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.RunState
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolOutcome
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/** Converts between Anu's JSON and ADK's plain maps and schemas (ADR-104). */
internal object AdkBridge {
    /** A JSON value as the plain Kotlin values ADK's tools and calls take. */
    fun plain(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonObject -> element.mapValues { plain(it.value) }
        is JsonArray -> element.map(::plain)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull ?: element.contentOrNull
        }
    }

    fun plain(obj: JsonObject): Map<String, Any?> = obj.mapValues { plain(it.value) }

    /** ADK's plain values as JSON, for the broker, which checks them against the tool's schema (I6). */
    fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        // A model writes 3 or 3.0 for an integer field; a whole number goes as one.
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        is Float -> json(value.toDouble())
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
        is Iterable<*> -> JsonArray(value.map(::json))
        is Array<*> -> JsonArray(value.map(::json))
        else -> JsonPrimitive(value.toString())
    }

    fun jsonObject(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { json(it.value) })

    /** Anu's generated JSON Schema (§7.1) as ADK's [Schema]: the subset Anu generates, types, fields, items and enums. */
    fun schema(json: JsonObject): Schema = Schema(
        type = when ((json["type"] as? JsonPrimitive)?.contentOrNull) {
            "string" -> Type.STRING
            "integer" -> Type.INTEGER
            "number" -> Type.NUMBER
            "boolean" -> Type.BOOLEAN
            "array" -> Type.ARRAY
            "object" -> Type.OBJECT
            else -> null
        },
        description = (json["description"] as? JsonPrimitive)?.contentOrNull,
        properties = (json["properties"] as? JsonObject)?.mapValues { (_, v) -> schema(v as JsonObject) },
        items = (json["items"] as? JsonObject)?.let(::schema),
        required = (json["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        enum = (json["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )

    /** ADK's [Schema] as JSON Schema, for a tool ADK declares itself, such as an agent used as a tool. */
    fun json(schema: Schema?): JsonObject = buildJsonObject {
        if (schema == null) {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            return@buildJsonObject
        }
        schema.type?.let { put("type", it.name.lowercase()) }
        schema.description?.let { put("description", it) }
        schema.properties?.let { props -> put("properties", JsonObject(props.mapValues { json(it.value) })) }
        schema.items?.let { put("items", json(it)) }
        schema.required?.let { put("required", JsonArray(it.map(::JsonPrimitive))) }
        schema.enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    }

    /**
     * ADK's contents as Anu's transcript. Function responses become a tool turn; text from the user or from another agent
     * becomes the user's turn, joined when two come in a row. A brain needs a transcript that ends with the user or a
     * tool, so a turn that ends with the model, or has nothing, asks it to go on.
     */
    fun transcript(contents: List<Content>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (content in contents) {
            val text = content.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
            val calls = content.parts.mapNotNull { it.functionCall }.map { ToolCall(it.name, jsonObject(it.args)) }
            val results = content.parts.mapNotNull { it.functionResponse }.map { ToolResult(it.name, jsonObject(it.response)) }
            when {
                results.isNotEmpty() -> out += ChatMessage.Tool(results)
                content.role == "model" -> if (text.isNotEmpty() || calls.isNotEmpty()) out += ChatMessage.Model(text, calls)
                text.isNotEmpty() -> {
                    val last = out.lastOrNull()
                    if (last is ChatMessage.User) out[out.lastIndex] = ChatMessage.User(last.text + "\n\n" + text) else out += ChatMessage.User(text)
                }
            }
        }
        if (out.isEmpty() || out.last() is ChatMessage.Model) out += ChatMessage.User(GO_ON)
        return out
    }

    const val GO_ON = "Go on."
}

/**
 * Anu's on-device brain as ADK's [Model] (ADR-104), so ADK's agents run on the phone's Gemma through Anu's own engine
 * and cache, never a second copy of the model. It answers in one response, with its text and any tool calls; ADK runs
 * the tools, each one through the broker ([BrokerTool]). Calls take turns on [engine]: the phone has one model engine,
 * and a ParallelAgent's branches would otherwise ask it at once. A cloud brain is never used: these turns stay on the
 * phone (MEDIA-1).
 */
class AnuModel(
    private val brain: () -> Brain?,
    private val engine: Mutex,
    override val name: String = "anu-on-device",
) : Model {
    private val ids = AtomicInteger()

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        val brain = brain()?.takeIf { it.capabilities.location == BrainLocation.ON_DEVICE }
            ?: error("ADK's agents need an on-device model, and none is installed")
        val tools = request.config.tools.orEmpty().flatMap { it.functionDeclarations.orEmpty() }
            .map { ToolSpec(it.name, it.description, AdkBridge.json(it.parameters)) }
        val system = request.config.systemInstruction?.parts?.mapNotNull { it.text }?.joinToString("\n")?.takeIf { it.isNotBlank() }
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        engine.withLock {
            brain.stream(
                BrainRequest(
                    system = system,
                    transcript = AdkBridge.transcript(request.contents),
                    tools = tools,
                    sampling = if (tools.isEmpty()) Sampling.Default else Sampling.Acting,
                    dataClasses = setOf(DataClass.PERSONAL),
                    purpose = "agents",
                ),
            ).collect { event ->
                when (event) {
                    is BrainEvent.TextDelta -> if (!event.thought) text.append(event.text)
                    is BrainEvent.ToolCallRequested -> calls += event.call
                    else -> Unit
                }
            }
        }
        val parts = buildList {
            text.toString().trim().takeIf { it.isNotEmpty() }?.let { add(Part(text = it)) }
            calls.forEach { call -> add(Part(functionCall = FunctionCall(call.name, AdkBridge.plain(call.arguments), "anu-call-${ids.incrementAndGet()}"))) }
        }
        emit(LlmResponse(content = Content(role = "model", parts = parts), finishReason = FinishReason.STOP))
    }
}

/**
 * What one orchestrated turn shares with its tools (ADR-104): the [broker] and the turn's [run] state, so taint from a
 * specialist's result reaches every later call (P4); the chat's events; and the receipts so far.
 */
class TurnBridge(
    val broker: ToolBroker,
    val run: RunState,
    private val events: suspend (AgentEvent) -> Unit,
) {
    private val done = mutableListOf<ActionRecord>()

    val actions: List<ActionRecord> get() = synchronized(done) { done.toList() }

    suspend fun emit(event: AgentEvent) = events(event)

    fun record(call: ToolCall, outcome: ToolOutcome) {
        val action = when (outcome) {
            is ToolOutcome.Ok -> ActionRecord(call.name, outcome.summary, ActionRecord.Status.DONE, outcome.wrote, destination = outcome.destination)
            is ToolOutcome.Rejected -> ActionRecord(call.name, outcome.summary, ActionRecord.Status.NOT_DONE, false, outcome.reason, outcome.destination)
            is ToolOutcome.Failed -> ActionRecord(call.name, outcome.summary, ActionRecord.Status.FAILED, false, outcome.reason, outcome.destination)
            is ToolOutcome.Invalid -> null
        }
        action?.let { synchronized(done) { done += it } }
    }
}

/**
 * One of Anu's tools as an ADK tool (ADR-104). ADK decides when to call it; the broker decides whether it runs: it
 * checks the arguments (I6), applies risk tiers and taint, asks for approval of the exact payload when needed (I2),
 * and audits, exactly as for the chat's own agent (P2). Its result goes back to the model as the broker's result JSON.
 */
class BrokerTool(private val spec: ToolSpec, private val turn: TurnBridge) : BaseTool(spec.name, spec.description) {
    override fun declaration(): FunctionDeclaration = FunctionDeclaration(name = name, description = description, parameters = AdkBridge.schema(spec.parameters))

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
        val call = ToolCall(name, AdkBridge.jsonObject(args))
        turn.emit(AgentEvent.ToolStarted(call))
        val outcome = turn.broker.execute(call, turn.run)
        turn.emit(AgentEvent.ToolFinished(call, outcome))
        turn.record(call, outcome)
        return AdkBridge.plain(outcome.toResultJson() as JsonObject)
    }
}
