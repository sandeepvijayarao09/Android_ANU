package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.router.Guard
import io.github.sandeepvijayarao09.anu.core.router.Tier

/**
 * What "About this answer" shows (ADR-071), saved with each answer: which model answered and where, what it
 * was given and did, and how fast it was. It keeps references and numbers, never content: facts by id, and
 * actions as the broker recorded them.
 */
data class AnswerDetails(
    val tier: Tier,
    /** The on-device model file's backend, such as CPU or GPU. Null for the cloud. */
    val backend: String? = null,
    /** The tier that answered first, when its answer didn't pass its checks (ADR-026). */
    val escalatedFrom: Tier? = null,
    /** The facts from memory given with the message, by id (ADR-054). */
    val factIds: List<Long> = emptyList(),
    /** Whether the memory summary was in the prompt (§8.3). Only on-device brains see it. */
    val summaryShown: Boolean = false,
    /** What tools did, from the broker's record (ADR-027). */
    val actions: List<ActionRecord> = emptyList(),
    /** From sending until the answer's first word appeared, by the phone's clock; for a voice note, from when its words were written down. */
    val firstWordMs: Long? = null,
    /** From sending until the answer was complete, writing down any voice note included. */
    val totalMs: Long? = null,
    /** What the answering step read and wrote, in tokens, and how long each took. */
    val read: Int? = null,
    val readMs: Long? = null,
    val written: Int? = null,
    val writeMs: Long? = null,
    /** True when [read] and [written] are the runtime's own counts, false when they are estimates. */
    val counted: Boolean = false,
    /** When the answer was completed. */
    val atMillis: Long = 0,
    /** The guard that shaped this answer, if one did: Base instead of Pro, or a shorter Pro answer (ADR-072). */
    val guard: Guard? = null,
    /** The guard's shorter limit cut this answer. */
    val cut: Boolean = false,
    /** A phone command done with no model (G6, ADR-076). [tier] means nothing then. */
    val direct: Boolean = false,
    /** How long the model reasoned before its answer began, when it did (G4, ADR-077). */
    val thoughtMs: Long? = null,
    /** How long the voice note took to write down before the model began, when there was one (G3, ADR-078). */
    val heardMs: Long? = null,
    /** The agents that worked on an orchestrated turn, in the order they began (ADR-110). Empty for the chat's own agent. */
    val agents: List<AgentStep> = emptyList(),
)

/**
 * One ADK agent's part in an orchestrated turn (ADR-110): its name, how many times it asked the model, and the tools
 * it ran, by name. A workflow step that runs a tool with no model has no model calls.
 */
data class AgentStep(val agent: String, val modelCalls: Int, val tools: List<String>)

/**
 * Where answers' details are kept, by the index in the transcript of the user message that asked. They are
 * written with the exchange and removed with it, as on a retry (§14).
 */
interface AnswerStore {
    suspend fun load(): Map<Int, AnswerDetails>

    suspend fun save(turn: Int, details: AnswerDetails)

    /** Removes the details of every turn from [turn] on. */
    suspend fun truncate(turn: Int)
}

class InMemoryAnswerStore : AnswerStore {
    private val answers = mutableMapOf<Int, AnswerDetails>()

    override suspend fun load(): Map<Int, AnswerDetails> = answers.toMap()

    override suspend fun save(turn: Int, details: AnswerDetails) {
        answers[turn] = details
    }

    override suspend fun truncate(turn: Int) {
        answers.keys.removeAll { it >= turn }
    }
}
