package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.coroutines.flow.Flow

/** A model that answers [BrainRequest]s. ARCHITECTURE §5. */
interface Brain {
    val id: BrainId
    val capabilities: BrainCapabilities

    /**
     * Cold stream: collecting starts generation and cancelling the collector stops it (I7).
     *
     * A successful stream emits any number of [BrainEvent.TextDelta] and
     * [BrainEvent.ToolCallRequested], then one [BrainEvent.Usage], then one [BrainEvent.Finished].
     * Errors are thrown from the flow. Tool calls are only reported, never executed (ADR-017).
     */
    fun stream(request: BrainRequest): Flow<BrainEvent>
}

enum class BrainId { BASE, PRO, CLOUD_FLASH, CLOUD_PRO }

/** Where a brain runs. The router and the egress policy depend on it (§6, §12). */
enum class BrainLocation { ON_DEVICE, CLOUD }

data class BrainCapabilities(
    val location: BrainLocation,
    val maxContextTokens: Int,
    val toolCalling: Boolean,
    val structuredOutput: Boolean,
)

sealed interface BrainEvent {
    data class TextDelta(val text: String) : BrainEvent

    data class ToolCallRequested(val call: ToolCall) : BrainEvent

    /**
     * Emitted once per request with totals, never running sums (§18).
     *
     * [contextTokens] is the model's context size after this request. On-device budgets use it.
     * [inputTokens] and [outputTokens] are null when the runtime doesn't report them.
     */
    data class Usage(
        val contextTokens: Int,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
    ) : BrainEvent

    data class Finished(val reason: StopReason) : BrainEvent
}

enum class StopReason { COMPLETE, TOOL_CALLS }
