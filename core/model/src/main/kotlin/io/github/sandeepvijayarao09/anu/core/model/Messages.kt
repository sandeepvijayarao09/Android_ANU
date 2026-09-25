package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One turn of the transcript the agent runtime persists. The transcript is the truth (ADR-018). */
sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage

    data class Model(val text: String, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage

    /** Results of the tool calls in the preceding [Model] turn. */
    data class Tool(val results: List<ToolResult>) : ChatMessage
}

data class ToolResult(val toolName: String, val result: JsonElement)

data class BrainRequest(
    val system: String? = null,
    /** The full transcript. It ends with the new [ChatMessage.User] or [ChatMessage.Tool] turn. */
    val transcript: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    /** When set, decoding is constrained to this JSON Schema (P6). */
    val responseSchema: JsonObject? = null,
    val maxOutputTokens: Int? = null,
    val sampling: Sampling = Sampling.Default,
    /** What the context contains. Cloud brains record this in the Privacy Ledger (I3). */
    val dataClasses: Set<DataClass> = emptySet(),
    /** Why this request is made, for example "chat" or "email". Shown in the Privacy Ledger. */
    val purpose: String = "chat",
) {
    init {
        require(transcript.isNotEmpty()) { "transcript must not be empty" }
        require(transcript.last() !is ChatMessage.Model) { "transcript must end with a User or Tool turn" }
    }
}

data class Sampling(val temperature: Double, val topP: Double, val topK: Int, val seed: Int = 0) {
    companion object {
        /** Gemma's published defaults. */
        val Default = Sampling(temperature = 1.0, topP = 0.95, topK = 64)

        /** Greedy decoding, for structured output and repair attempts (P6). */
        val Precise = Sampling(temperature = 0.0, topP = 1.0, topK = 1)
    }
}
