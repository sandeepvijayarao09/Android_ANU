package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.model.onDeviceSystem
import kotlin.math.ceil

/**
 * Raw token estimates (§7.3), on the safe side before any calibration. Budgets multiply them by
 * [TokenCalibration.scale], learned from the runtime's own counts (ADR-041).
 * - Prose at 3.5 characters a token. Gemma measured about 4 on the emulator.
 * - JSON (tool definitions, tool calls and results) at 2.5. It costs more per character, and the
 *   emulator showed a single rate underestimating tool turns by about 20% after prose turns.
 */
object Tokens {
    fun of(text: String?): Int = count(text, PROSE_CHARS_PER_TOKEN)

    fun of(message: ChatMessage): Int = PER_MESSAGE + when (message) {
        is ChatMessage.User -> of(message.text)
        is ChatMessage.Model -> of(message.text) + message.toolCalls.sumOf { json(it.name) + json(it.arguments.toString()) }
        is ChatMessage.Tool -> message.results.sumOf { json(it.toolName) + json(it.result.toString()) }
    }

    fun of(messages: List<ChatMessage>): Int = messages.sumOf { of(it) }

    fun ofTools(specs: List<ToolSpec>): Int = specs.sumOf { PER_TOOL + json(it.name) + of(it.description) + json(it.parameters.toString()) }

    private fun json(text: String?): Int = count(text, JSON_CHARS_PER_TOKEN)

    private fun count(text: String?, charsPerToken: Double): Int = if (text.isNullOrEmpty()) 0 else ceil(text.length / charsPerToken).toInt()

    /** Everything a brain is sent for [request]: system prompt, memory, tools, transcript, and the time on the newest message. */
    fun ofRequest(request: BrainRequest): Int =
        of(request.onDeviceSystem) + ofTools(request.tools) + of(request.transcript) + (request.sentAt?.let { of(it) + 2 } ?: 0)

    private const val PROSE_CHARS_PER_TOKEN = 3.5
    private const val JSON_CHARS_PER_TOKEN = 2.5

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
     * message. Both are raw estimates, and [scale] turns them into the runtime's tokens (ADR-041). If not
     * even the newest exchange fits, the view is empty and only the new message is sent.
     */
    fun start(history: List<ChatMessage>, previous: Int, fixed: Int, scale: Double = 1.0): Int {
        // tail[i] is the estimated size of history[i..].
        val tail = IntArray(history.size + 1)
        for (i in history.indices.reversed()) tail[i] = tail[i + 1] + Tokens.of(history[i])
        val starts = history.indices.filter { history[it] is ChatMessage.User } + history.size
        // A start past the end means exchanges were taken back since, so fit the window afresh.
        val from = if (previous > history.size) 0 else previous.coerceAtLeast(0)
        val current = starts.first { it >= from }
        fun size(from: Int) = ceil((fixed.toLong() + tail[from]) * scale)
        if (size(current) <= budget) return current
        val target = budget * refill
        return starts.firstOrNull { it > current && size(it) <= target } ?: history.size
    }
}

/** What the model was shown last turn, for the chat screen and diagnostics (ADR-040). */
data class ContextView(
    /** The index of the first saved message the model saw. More than 0 means older messages were left out. */
    val shownFrom: Int = 0,
    /** How many messages the chat has saved. */
    val messages: Int = 0,
    /** The estimated prompt size, including the system prompt, memory and tools, in the runtime's tokens. */
    val tokens: Int = 0,
    val budget: Int = Int.MAX_VALUE,
    /** What raw estimates were multiplied by (ADR-041). */
    val scale: Double = 1.0,
    /** The last on-device turn's estimate against the runtime's count. */
    val lastSample: TokenSample? = null,
    /** How many facts from memory travelled with the newest message (ADR-054). */
    val relevantFacts: Int = 0,
) {
    val trimmed: Boolean get() = shownFrom > 0
}

/** One turn's size: the raw estimate of what was sent and generated, and what the runtime counted (ADR-041). */
data class TokenSample(val estimated: Int, val counted: Int, val onDevice: Boolean)

/**
 * Keeps token estimates honest (ADR-041). After each on-device turn, the runtime's own count is compared
 * with the raw estimate of the same prompt and answer. The first ratio is taken as is, later ones are
 * smoothed in, and outliers are clamped. Budgets multiply raw estimates by [scale], which adds a margin.
 * Cloud counts are left out: Gemini's tokenizer isn't the one the on-device budgets are for.
 */
class TokenCalibration(
    /** A ratio learned before, or null to start from the raw estimate. */
    initial: Double? = null,
    /** Called with each new ratio, so it can be saved. */
    private val onChange: (Double) -> Unit = {},
    private val learns: Boolean = true,
) {
    @Volatile
    var ratio: Double = initial?.coerceIn(MIN_RATIO, MAX_RATIO) ?: 1.0
        private set

    @Volatile
    private var learned = initial != null

    /** What raw estimates are multiplied by. The margin covers what the learned ratio can't yet know. */
    val scale: Double get() = if (learns) ratio * MARGIN else ratio

    fun observe(sample: TokenSample) {
        if (!learns || !sample.onDevice || sample.estimated < MIN_ESTIMATE || sample.counted <= 0) return
        val observed = (sample.counted.toDouble() / sample.estimated).coerceIn(MIN_RATIO, MAX_RATIO)
        ratio = if (learned) ratio + SMOOTHING * (observed - ratio) else observed
        learned = true
        onChange(ratio)
    }

    companion object {
        /** Raw estimates as they are, never learning. */
        val NONE = TokenCalibration(initial = 1.0, learns = false)

        /** How much each new turn moves the ratio. */
        const val SMOOTHING = 0.3
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 2.5

        /** Small turns are noisy: the chat template's fixed markers weigh too much in them. */
        const val MIN_ESTIMATE = 200
        private const val MARGIN = 1.05
    }
}
