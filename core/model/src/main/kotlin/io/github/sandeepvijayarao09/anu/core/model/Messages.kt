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
    /**
     * The user's local time when they sent the newest user message, for example "Fri 2026-09-25 14:46".
     * Brains show it with that message (see [userText]) rather than in [system], so the system prompt
     * stays the same all day and the on-device KV cache survives from one turn to the next (ADR-018).
     */
    val sentAt: String? = null,
    /**
     * What Anu remembers about the user, as a ready-to-read block (§8.3). On-device brains add it to
     * the system instruction ([onDeviceSystem]). Cloud brains never send it: the profile stays on the
     * phone (§8.4, ADR-038).
     */
    val memory: String? = null,
    /**
     * Facts from memory that bear on the newest user message (§8.3, ADR-054), as a ready-to-read block.
     * On-device brains show it with that message, like [sentAt], so the system prompt stays the same
     * and the KV cache survives. Cloud brains never send it.
     */
    val relevant: String? = null,
    /**
     * The facts the user saw on the cloud consent sheet and chose to share for this request (ADR-054):
     * only facts relevant to the message, never sensitive ones. Cloud brains send them with the newest
     * message; the egress gate replaces personal details in them and the Privacy Ledger counts them (I3).
     */
    val sharedFacts: List<String> = emptyList(),
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

/** Index of the newest user message in [BrainRequest.transcript], the one [BrainRequest.sentAt] belongs to. */
val BrainRequest.newestUserIndex: Int get() = transcript.indexOfLast { it is ChatMessage.User }

/**
 * The text a brain shows the model for the user message at [index]. The newest one starts with
 * [BrainRequest.sentAt], after [facts]: the [BrainRequest.relevant] block on the phone, the block of
 * [BrainRequest.sharedFacts] in the cloud.
 */
fun BrainRequest.userText(index: Int, facts: String? = relevant): String {
    val text = (transcript[index] as ChatMessage.User).text
    if (index != newestUserIndex) return text
    val stamped = if (sentAt != null) "[$sentAt] $text" else text
    return if (facts != null) "$facts\n\n$stamped" else stamped
}

/** Heads the facts shown with a message (ADR-054). */
const val FACTS_HEADER = "Facts from memory about this message (the user's own data):"

/** How facts from memory read with a message: the header, then one fact per line. Null for none. */
fun factsBlock(facts: List<String>): String? =
    if (facts.isEmpty()) null else (listOf(FACTS_HEADER) + facts.map { "- $it" }).joinToString("\n")

/** A fact from memory that bears on a message (§8.3, ADR-054). A [sensitive] one never leaves the phone. */
data class RelevantFact(val text: String, val sensitive: Boolean)

/** The system instruction an on-device brain uses: [BrainRequest.system], then [BrainRequest.memory]. */
val BrainRequest.onDeviceSystem: String?
    get() = listOfNotNull(system, memory).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
