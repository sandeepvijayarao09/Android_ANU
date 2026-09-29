package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.coroutines.flow.Flow

/**
 * The prompt lab (G7, ADR-080): one prompt at a time on a model on this phone, to try instructions and sampling. It has
 * no memory, tools, connectors or history, keeps nothing, and never reaches the cloud.
 */
interface PromptLab {
    /** The on-device models installed now, which the lab can run. */
    fun models(): List<BrainId>

    /** Runs [request] on [id] in a conversation of its own, beside the chat's; cancelling stops it (I7). */
    fun run(id: BrainId, request: BrainRequest): Flow<BrainEvent>
}

/**
 * A lab request: [prompt] under an optional [instruction], with the sampling to try. By construction it carries no
 * memory, facts, tools or history, whatever the chat holds (LAB-1).
 */
fun labRequest(instruction: String, prompt: String, sampling: Sampling, maxOutputTokens: Int, thinking: Boolean) = BrainRequest(
    system = instruction.trim().ifEmpty { null },
    transcript = listOf(ChatMessage.User(prompt.trim())),
    sampling = sampling,
    maxOutputTokens = maxOutputTokens,
    thinking = thinking,
    purpose = "lab",
)
