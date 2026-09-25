package io.github.sandeepvijayarao09.anu.brain.litertlm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall

internal fun ChatMessage.toLiteRt(): Message = when (this) {
    is ChatMessage.User -> Message.user(text)
    is ChatMessage.Model -> if (text.isEmpty()) {
        Message.model(toolCalls = toolCalls.map { it.toLiteRt() })
    } else {
        Message.model(Contents.of(text), toolCalls.map { it.toLiteRt() })
    }
    is ChatMessage.Tool -> Message.tool(
        Contents.of(results.map { Content.ToolResponse(it.toolName, it.result.toPlain()) }),
    )
}

internal fun ToolCall.toLiteRt(): LiteRtToolCall = LiteRtToolCall(name, arguments.mapValues { it.value.toPlain() })

internal fun LiteRtToolCall.toCore(): ToolCall = ToolCall(name, arguments.toJsonElement() as JsonObject)

internal fun Sampling.toLiteRt(): SamplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature, seed = seed)

/** Text parts of a streamed message, without tool calls or thinking channels. */
internal fun Message.text(): String = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/**
 * Tells the model about a tool without letting the runtime run it. Anu always sets
 * `automaticToolCalling = false`, and the Tool Broker executes every call (ADR-017).
 */
internal class DeclaredTool(private val spec: ToolSpec) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", spec.name)
        put("description", spec.description)
        put("parameters", spec.parameters)
    }.toString()

    override fun execute(paramsJsonString: String): String =
        throw IllegalStateException("${spec.name} must be executed by the Tool Broker, not by LiteRT-LM (ADR-017)")
}
