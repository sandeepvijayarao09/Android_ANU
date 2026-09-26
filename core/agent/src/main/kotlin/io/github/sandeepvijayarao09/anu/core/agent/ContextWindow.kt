package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import kotlin.math.ceil

/**
 * Token estimates for budgets (§7.3). Three and a half characters a token is on the safe side for
 * English, and for the JSON of tool calls and results, which cost more per character. It is a
 * hypothesis until the runtime's own counts calibrate it (S1).
 */
object Tokens {
    fun of(text: String?): Int = if (text.isNullOrEmpty()) 0 else ceil(text.length / CHARS_PER_TOKEN).toInt()

    fun of(message: ChatMessage): Int = PER_MESSAGE + when (message) {
        is ChatMessage.User -> of(message.text)
        is ChatMessage.Model -> of(message.text) + message.toolCalls.sumOf { of(it.name) + of(it.arguments.toString()) }
        is ChatMessage.Tool -> message.results.sumOf { of(it.toolName) + of(it.result.toString()) }
    }

    fun of(messages: List<ChatMessage>): Int = messages.sumOf { of(it) }

    fun ofTools(specs: List<ToolSpec>): Int = specs.sumOf { PER_TOOL + of(it.name) + of(it.description) + of(it.parameters.toString()) }

    private const val CHARS_PER_TOKEN = 3.5

    /** The chat template's turn markers. */
    private const val PER_MESSAGE = 4
    private const val PER_TOOL = 8
}

/**
 * Which of the chat's past messages the model sees (§7.3, ADR-040). The whole chat stays saved and on
 * screen. Only the model's view is trimmed, so a long chat never outgrows the on-device context, and
 * the cloud is sent no more than it needs (I3).
 * - Only whole exchanges are dropped, oldest first. An exchange starts at a user message, so a tool
 *   call is never separated from its result.
 * - The start stays where it was while everything fits, so the model's KV cache keeps matching
 *   (ADR-033). When it no longer fits, old exchanges are dropped until the rest fits in [refill] of the
 *   budget, which puts the next trim several turns away.
 */
class ContextWindow(
    /** Prompt tokens a turn may use, including the system prompt, memory and tool definitions. */
    val budget: Int = Int.MAX_VALUE,
    private val refill: Double = 0.6,
) {
    init {
        require(budget > 0) { "the budget must be positive" }
        require(refill > 0.0 && refill <= 1.0) { "refill is a share of the budget" }
    }

    /**
     * Where the model's view of [history] starts this turn. [previous] is where it started last turn.
     * [fixed] is what every turn carries besides the history: system prompt, memory, tools and the new
     * message. If not even the newest exchange fits, the view is empty and only the new message is sent.
     */
    fun start(history: List<ChatMessage>, previous: Int, fixed: Int): Int {
        // tail[i] is the estimated size of history[i..].
        val tail = IntArray(history.size + 1)
        for (i in history.indices.reversed()) tail[i] = tail[i + 1] + Tokens.of(history[i])
        val starts = history.indices.filter { history[it] is ChatMessage.User } + history.size
        // A start past the end means exchanges were taken back since, so fit the window afresh.
        val from = if (previous > history.size) 0 else previous.coerceAtLeast(0)
        val current = starts.first { it >= from }
        if (fixed.toLong() + tail[current] <= budget) return current
        val target = (budget * refill).toLong()
        return starts.firstOrNull { it > current && fixed.toLong() + tail[it] <= target } ?: history.size
    }
}

/** What the model was shown last turn, for the chat screen and diagnostics (ADR-040). */
data class ContextView(
    /** The index of the first saved message the model saw. More than 0 means older messages were left out. */
    val shownFrom: Int = 0,
    /** How many messages the chat has saved. */
    val messages: Int = 0,
    /** The estimated prompt size, including the system prompt, memory and tools. */
    val tokens: Int = 0,
    val budget: Int = Int.MAX_VALUE,
) {
    val trimmed: Boolean get() = shownFrom > 0
}
