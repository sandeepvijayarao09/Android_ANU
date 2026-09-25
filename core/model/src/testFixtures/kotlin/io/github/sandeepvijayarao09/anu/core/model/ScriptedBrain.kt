package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * A deterministic [Brain] for tests. It plays back one [Step] per request and records every request
 * and every cancellation. Its event order follows the [Brain.stream] contract.
 */
class ScriptedBrain(
    override val id: BrainId,
    override val capabilities: BrainCapabilities = BrainCapabilities(
        location = if (id == BrainId.CLOUD_FLASH || id == BrainId.CLOUD_PRO) BrainLocation.CLOUD else BrainLocation.ON_DEVICE,
        maxContextTokens = 8_192,
        toolCalling = true,
        structuredOutput = true,
    ),
    steps: List<Step> = emptyList(),
) : Brain {

    sealed interface Step {
        data class Reply(val chunks: List<String>, val toolCalls: List<ToolCall> = emptyList()) : Step {
            constructor(text: String) : this(listOf(text))
        }

        data class Fail(val error: Throwable) : Step

        /** Streams [chunks], then waits until the collector cancels. */
        data class Hang(val chunks: List<String> = listOf("…")) : Step
    }

    private val steps = ArrayDeque(steps)
    val requests = mutableListOf<BrainRequest>()
    var cancellations = 0
        private set

    fun enqueue(vararg more: Step) = more.forEach(steps::addLast)

    override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
        requests += request
        when (val step = steps.removeFirstOrNull() ?: error("ScriptedBrain $id has no step for request ${requests.size}")) {
            is Step.Reply -> {
                step.chunks.forEach { emit(BrainEvent.TextDelta(it)) }
                step.toolCalls.forEach { emit(BrainEvent.ToolCallRequested(it)) }
                emit(BrainEvent.Usage(contextTokens = step.chunks.sumOf { it.length }))
                emit(BrainEvent.Finished(if (step.toolCalls.isEmpty()) StopReason.COMPLETE else StopReason.TOOL_CALLS))
            }
            is Step.Fail -> throw step.error
            is Step.Hang -> {
                step.chunks.forEach { emit(BrainEvent.TextDelta(it)) }
                awaitCancellation()
            }
        }
    }.onCompletion { if (it is CancellationException) cancellations++ }
}
