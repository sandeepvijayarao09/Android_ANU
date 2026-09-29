package io.github.sandeepvijayarao09.anu.brain.litertlm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Photo
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.model.userText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall

/** The transcript message at [index] as LiteRT-LM sees it, with the newest user message's time (ADR-018). */
internal fun BrainRequest.messageAt(index: Int): Message = when (val message = transcript[index]) {
    is ChatMessage.User -> userMessage(userText(index), message.photos)
    else -> message.toLiteRt()
}

/** A user message: its photos first, as the vision encoder expects, then the words (G2, ADR-075). */
private fun userMessage(text: String, photos: List<Photo>): Message {
    val pictures = photos.mapNotNull { it.bytes }.map { Content.ImageBytes(it) }
    return if (pictures.isEmpty()) Message.user(text) else Message.user(Contents.of(pictures + Content.Text(text)))
}

/** Whether a request shows the model a photo, so its engine needs the vision encoder. */
internal val BrainRequest.hasPhotos: Boolean
    get() = transcript.any { it is ChatMessage.User && it.photos.any { photo -> photo.bytes != null } }

internal fun ChatMessage.toLiteRt(): Message = when (this) {
    is ChatMessage.User -> userMessage(text, photos)
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
