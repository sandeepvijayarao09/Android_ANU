package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One turn of the transcript the agent runtime persists. The transcript is the truth (ADR-018). */
sealed interface ChatMessage {
    /**
     * The user's message, with any [photos] they attached (G2, ADR-075), any [voiceNote], a recording written down on
     * the phone (G3, EXP-3, ADR-078), and any [screen] it asked about (EXP-2, ADR-083). None ever leaves the phone
     * (MEDIA-1). Models read [modelText].
     */
    data class User(
        val text: String,
        val photos: List<Photo> = emptyList(),
        val voiceNote: VoiceNote? = null,
        val screen: Screen? = null,
    ) : ChatMessage

    data class Model(val text: String, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage

    /** Results of the tool calls in the preceding [Model] turn. */
    data class Tool(val results: List<ToolResult>) : ChatMessage
}

/**
 * A photo the user attached (G2, ADR-075), by its id in Anu's encrypted store. [bytes] is the picture itself, a JPEG
 * at most 1,024 pixels on its longer side, loaded only while a brain needs it. Two photos are the same photo when
 * their ids are. A photo taints every turn that shows it (P4), and never goes to the cloud (MEDIA-1).
 */
class Photo(val id: Long, val bytes: ByteArray? = null) {
    override fun equals(other: Any?): Boolean = other is Photo && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Photo($id)"
}

/**
 * The screen the user asked about from the assistant card (EXP-2, ADR-083): the [app] it showed, and its picture with
 * any codes masked, while this turn has it. The picture is never saved: a saved message keeps only the app and when,
 * so later turns know a screen was shown, stay on the phone and stay tainted. Two are the same when their app and
 * time are.
 */
class Screen(val app: String, val atMillis: Long, val image: ByteArray? = null) {
    override fun equals(other: Any?): Boolean = other is Screen && other.app == app && other.atMillis == atMillis

    override fun hashCode(): Int = 31 * app.hashCode() + atMillis.hashCode()

    override fun toString(): String = "Screen($app, $atMillis)"
}

/**
 * One tool's result. [untrusted] is the kind of content from other people it holds, as the broker knew
 * when the call ran, or null when it holds none (P4). [untrustedKnown] is false for results saved before
 * Anu recorded that, so readers go by the tool's name instead.
 */
data class ToolResult(
    val toolName: String,
    val result: JsonElement,
    val untrusted: UntrustedContent? = null,
    val untrustedKnown: Boolean = true,
)

data class BrainRequest(
    val system: String? = null,
    /** The full transcript. It ends with the new [ChatMessage.User] or [ChatMessage.Tool] turn. */
    val transcript: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    /** When set, decoding is constrained to this JSON Schema (P6). */
    val responseSchema: JsonObject? = null,
    val maxOutputTokens: Int? = null,
    /**
     * Whether the model reasons before answering (G4, ADR-077), through its own chat template (I8). On-device brains
     * stream the reasoning apart from the answer and keep it out of the next turn; cloud brains ignore it.
     */
    val thinking: Boolean = false,
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
    val text = (transcript[index] as ChatMessage.User).modelText
    if (index != newestUserIndex) return text
    val stamped = if (sentAt != null) "[$sentAt] $text" else text
    return if (facts != null) "$facts\n\n$stamped" else stamped
}

/**
 * Whether [answer] quotes the framing [userText] adds to the newest message: its send time in brackets,
 * or the facts header (ADR-058). A sampled model sometimes copies it; such an answer is invalid output.
 */
fun BrainRequest.echoesFraming(answer: String): Boolean = sentAt != null && "[$sentAt]" in answer || FACTS_HEADER in answer

/** [answer] without the framing it quoted, for an answer that still quotes it after a retry. */
fun BrainRequest.withoutFraming(answer: String): String {
    var out = answer.replace(FACTS_HEADER, "")
    if (sentAt != null) out = out.replace("[$sentAt] ", "").replace("[$sentAt]", "")
    return out.trim()
}

/** Heads the facts shown with a message (ADR-054). */
const val FACTS_HEADER = "Facts from memory about this message (the user's own data):"

/** How facts from memory read with a message: the header, then one fact per line. Null for none. */
fun factsBlock(facts: List<String>): String? =
    if (facts.isEmpty()) null else (listOf(FACTS_HEADER) + facts.map { "- $it" }).joinToString("\n")

/**
 * A fact from memory that bears on a message (§8.3, ADR-054). A [sensitive] one never leaves the phone.
 * [id] lets "About this answer" refer to the fact rather than keep a copy of its text (ADR-071).
 */
data class RelevantFact(val text: String, val sensitive: Boolean, val id: Long? = null)

/** The system instruction an on-device brain uses: [BrainRequest.system], then [BrainRequest.memory]. */
val BrainRequest.onDeviceSystem: String?
    get() = listOfNotNull(system, memory).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
