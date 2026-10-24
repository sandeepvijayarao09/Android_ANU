package io.github.sandeepvijayarao09.anu.brain.litertlm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.model.modelText
import io.github.sandeepvijayarao09.anu.core.model.onDeviceSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * One on-device turn's speed, from the runtime's own counters (debug builds, §15): the tokens it read and at what
 * rate, how long the first token took from sending, and the tokens it wrote. Numbers only, never content.
 */
data class TurnSpeed(
    val read: Int,
    val readPerSecond: Double,
    val firstTokenMs: Long,
    val written: Int,
    val writtenPerSecond: Double,
    /** Whether the turn kept the cached conversation, and if not which parts differed (ADR-097). Names only. */
    val conversation: String = "",
)

/**
 * Gemma 4 E2B or E4B running through LiteRT-LM (ARCHITECTURE §5).
 *
 * - Tools are declared to the model but never run by the runtime (ADR-017).
 * - One LiteRT-LM conversation is kept as a KV cache and reused when a request extends the
 *   transcript it has already seen. Otherwise it is rebuilt from the transcript (ADR-018).
 * - Cancelling the flow calls `cancelProcess()` and waits for the runtime to confirm. Only then is
 *   the conversation closed, because it can't be rolled back (I7).
 */
class LiteRtLmBrain(
    override val id: BrainId,
    override val capabilities: BrainCapabilities,
    private val engines: EngineHolder,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Told each turn's speed when the engine keeps the runtime's counters ([EngineOptions.benchmark]). */
    private val onTurn: ((TurnSpeed) -> Unit)? = null,
    /** The model file's backend, such as CPU or GPU, reported with each turn for "About this answer" (ADR-071). */
    private val backend: String? = null,
    /** Told when a turn starts and stops reading a whole chat again, so the wait can say so (ADR-074). */
    private val onReading: ((Boolean) -> Unit)? = null,
) : Brain {

    /** One generation at a time per model. It also guards [cached]. */
    private val generation = Mutex()

    /**
     * The conversations kept as KV caches, least recently used first, at most [KEPT] (ADR-097). Two, so a one-message
     * job such as Writing or Test speed doesn't throw away the chat's, and the chat's next turn doesn't read it again.
     */
    private val cached = ArrayDeque<CachedConversation>()

    override fun stream(request: BrainRequest): Flow<BrainEvent> = callbackFlow {
        generation.lock()
        // Completes when the runtime has finished the turn: true on success, false on error or cancel.
        val finished = CompletableDeferred<Boolean>()
        var session: CachedConversation? = null
        try {
            // A conversation built again from the chat reads all of it before the first word, and building it takes
            // time too (about 30 s for a long chat on the emulator), so the wait is named from the start (ADR-074).
            val rereading = request.transcript.size > 1 && !reusable(request)
            if (rereading) onReading?.invoke(true)
            val active = acquire(engines.get(), request)
            session = active
            val reply = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            val sent = AtomicLong()
            val first = AtomicLong()
            val heard = AtomicLong(System.nanoTime())
            // Text arrives one decoded token at a time, so counting the pieces estimates what was written.
            var pieces = 0
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    heard.set(System.nanoTime())
                    if (first.compareAndSet(0, System.nanoTime()) && rereading) onReading?.invoke(false)
                    // The reasoning comes in its own channel, apart from the answer (G4, ADR-077).
                    message.channels[THOUGHT]?.takeIf(String::isNotEmpty)?.let { trySend(BrainEvent.TextDelta(it, thought = true)) }
                    val delta = message.text()
                    if (delta.isNotEmpty()) {
                        pieces++
                        reply.append(delta)
                        trySend(BrainEvent.TextDelta(delta))
                    }
                    for (call in message.toolCalls) {
                        val coreCall = call.toCore()
                        calls += coreCall
                        trySend(BrainEvent.ToolCallRequested(coreCall))
                    }
                }

                override fun onDone() {
                    val done = System.nanoTime()
                    active.history = request.transcript + ChatMessage.Model(reply.toString(), calls.toList())
                    // A cancel the runtime confirms after the grace can end here once its engine is let go (an unload
                    // in the background, as when the keyboard hides): the counts are lost, and the app must not be.
                    val counters = runCatching { speed(active.conversation, sent.get(), first.get()) }.getOrNull()?.copy(conversation = active.why)
                    counters?.let { speed -> onTurn?.invoke(speed) }
                    val tokens = runCatching { active.conversation.getTokenCount() }.getOrDefault(active.tokens)
                    trySend(usage(tokens, active.tokens, pieces, counters, sent.get(), first.get(), done))
                    active.tokens = tokens
                    trySend(BrainEvent.Finished(if (calls.isEmpty()) StopReason.COMPLETE else StopReason.TOOL_CALLS))
                    finished.complete(true)
                    channel.close()
                }

                override fun onError(throwable: Throwable) {
                    finished.complete(false)
                    close(throwable)
                }
            }
            try {
                sent.set(System.nanoTime())
                active.conversation.sendMessageAsync(
                    request.messageAt(request.transcript.lastIndex),
                    callback,
                    maxOutputToken = request.maxOutputTokens,
                    responseFormat = request.responseSchema?.let { ResponseFormat.json(it.toString()) },
                )
            } catch (t: Throwable) {
                finished.complete(false)
                throw t
            }
            watch(heard, finished) { runCatching { active.conversation.cancelProcess() } }
            awaitClose {
                if (!finished.isCompleted) runCatching { active.conversation.cancelProcess() }
            }
        } finally {
            withContext(NonCancellable) {
                onReading?.invoke(false)
                session?.let { release(it, finished) }
                generation.unlock()
            }
        }
    }.flowOn(dispatcher)

    /**
     * Writes one recording down (G3, ADR-078) in a conversation of its own ([alone]). Greedy, with no tools, memory or
     * system prompt, and a short answer limit. The model's no-speech marker becomes nothing, so the words are held
     * back until they differ from it.
     */
    fun transcribe(wav: ByteArray): Flow<String> = flow {
        val said = StringBuilder()
        var streaming = false
        val note = Message.user(Contents.of(Content.AudioBytes(wav), Content.Text(TRANSCRIBE)))
        alone(ConversationConfig(samplerConfig = Sampling.Precise.toLiteRt()), note, TRANSCRIPT_TOKENS).collect { piece ->
            val delta = (piece as? Piece.Part)?.message?.text().orEmpty()
            if (delta.isEmpty()) return@collect
            said.append(delta)
            when {
                streaming -> emit(delta)
                !NO_SPEECH.startsWith(said.toString().trimStart()) -> {
                    streaming = true
                    emit(said.toString().trimStart())
                }
            }
        }
    }

    /**
     * One request in a conversation of its own ([alone]): the prompt lab's (G7, ADR-080), with its own instruction and
     * sampling and nothing else, no history, tools or memory. It streams like [stream], the reasoning apart, then its
     * usage.
     */
    fun once(request: BrainRequest): Flow<BrainEvent> = flow {
        val config = ConversationConfig(
            systemInstruction = request.system?.let { Contents.of(it) },
            samplerConfig = request.sampling.toLiteRt(),
            thinkingConfig = ThinkingConfig(enableThinking = request.thinking, thinkingTokenBudget = THINKING_BUDGET),
        )
        alone(config, request.messageAt(request.transcript.lastIndex), request.maxOutputTokens).collect { piece ->
            when (piece) {
                is Piece.Part -> {
                    piece.message.channels[THOUGHT]?.takeIf(String::isNotEmpty)?.let { emit(BrainEvent.TextDelta(it, thought = true)) }
                    piece.message.text().takeIf(String::isNotEmpty)?.let { emit(BrainEvent.TextDelta(it)) }
                }
                is Piece.End -> {
                    emit(piece.usage)
                    emit(BrainEvent.Finished(StopReason.COMPLETE))
                }
            }
        }
    }

    /** What [alone] streams: each piece of the answer, then how the turn ran. */
    private sealed interface Piece {
        class Part(val message: Message) : Piece

        class End(val usage: BrainEvent.Usage) : Piece
    }

    /**
     * Sends [ask] in a conversation of its own (ADR-078, ADR-080), beside the chat's cached one, which the engine
     * keeps as it is (S6: both can be open, and the chat still knew what it had been told). It holds [generation], so
     * it never runs beside a chat turn. When the runtime is done it closes; cancelling stops the runtime first, and
     * only then closes the conversation (I7).
     */
    private fun alone(config: ConversationConfig, ask: Message, maxOutputTokens: Int?): Flow<Piece> = callbackFlow {
        generation.lock()
        val finished = CompletableDeferred<Boolean>()
        var open: Conversation? = null
        try {
            val conversation = engines.get().createConversation(config)
            open = conversation
            val sent = AtomicLong()
            val first = AtomicLong()
            val heard = AtomicLong(System.nanoTime())
            var pieces = 0
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    heard.set(System.nanoTime())
                    first.compareAndSet(0, System.nanoTime())
                    if (message.text().isNotEmpty()) pieces++
                    trySend(Piece.Part(message))
                }

                override fun onDone() {
                    val done = System.nanoTime()
                    val counters = runCatching { speed(conversation, sent.get(), first.get()) }.getOrNull()
                    val tokens = runCatching { conversation.getTokenCount() }.getOrDefault(0)
                    trySend(Piece.End(usage(tokens, 0, pieces, counters, sent.get(), first.get(), done)))
                    finished.complete(true)
                    channel.close()
                }

                override fun onError(throwable: Throwable) {
                    finished.complete(false)
                    close(throwable)
                }
            }
            try {
                sent.set(System.nanoTime())
                conversation.sendMessageAsync(ask, callback, maxOutputToken = maxOutputTokens)
            } catch (t: Throwable) {
                finished.complete(false)
                throw t
            }
            watch(heard, finished) { runCatching { conversation.cancelProcess() } }
            awaitClose {
                if (!finished.isCompleted) runCatching { conversation.cancelProcess() }
            }
        } finally {
            withContext(NonCancellable) {
                open?.let { conversation ->
                    if (withTimeoutOrNull(CANCEL_GRACE_MS) { finished.await() } == null) {
                        finished.invokeOnCompletion { conversation.close() }
                    } else {
                        conversation.close()
                    }
                }
                generation.unlock()
            }
        }
    }.flowOn(dispatcher)

    /**
     * Ends a turn whose runtime has gone quiet. On the emulator, LiteRT-LM timed out a task inside itself and then never
     * called back, so the answer waited forever. Every piece of the answer resets the clock; [STALL_MS] of silence
     * cancels the runtime's work and fails the turn. The conversation isn't closed here: [release] closes it only once
     * the runtime confirms, never mid-work (I7).
     */
    private fun <T> ProducerScope<T>.watch(heard: AtomicLong, finished: CompletableDeferred<Boolean>, cancel: () -> Unit) {
        launch {
            while (!finished.isCompleted) {
                delay(STALL_CHECK_MS)
                if (!finished.isCompleted && System.nanoTime() - heard.get() > STALL_MS * 1_000_000) {
                    cancel()
                    close(ModelStalled())
                    return@launch
                }
            }
        }
    }

    /** Frees the cached conversation, for example before the residency policy unloads the model. */
    suspend fun reset() = generation.withLock {
        cached.forEach { it.conversation.close() }
        cached.clear()
    }

    /** Whether the cached conversation already holds everything before [request]'s new message. Callers hold [generation]. */
    private fun reusable(request: BrainRequest): Boolean {
        val key = ConversationKey(request.onDeviceSystem, request.tools, request.sampling, request.thinking)
        val history = request.transcript.dropLast(1)
        return cached.any { it.key == key && it.history == history }
    }

    private fun acquire(engine: Engine, request: BrainRequest): CachedConversation {
        val system = request.onDeviceSystem
        val key = ConversationKey(system, request.tools, request.sampling, request.thinking)
        val history = request.transcript.dropLast(1)
        cached.firstOrNull { it.key == key && it.history == history }?.let { kept ->
            cached.remove(kept)
            return kept.also { it.why = "kept" }
        }
        val previous = cached.lastOrNull()
        // The new one and the kept ones stay within [KEPT]; S6 showed the engine keeps two open.
        while (cached.size >= KEPT) cached.removeFirst().conversation.close()
        val config = ConversationConfig(
            systemInstruction = system?.let { Contents.of(it) },
            initialMessages = history.indices.map(request::messageAt),
            tools = request.tools.map { tool(DeclaredTool(it)) },
            samplerConfig = request.sampling.toLiteRt(),
            automaticToolCalling = false,
            enableResponseFormat = true,
            // The model's own template turns thinking on (I8); its budget fits the answer's reserve (ADR-040).
            thinkingConfig = ThinkingConfig(enableThinking = request.thinking, thinkingTokenBudget = THINKING_BUDGET),
        )
        val conversation = withoutThoughtsInCache { engine.createConversation(config) }
        return CachedConversation(key, conversation, history).also { it.why = rebuilt(previous, key, history) + ": " + parts(request) }
    }

    /**
     * What a conversation built from the chat reads, part by part, in characters (about four to a token), so the
     * debug speed line shows where its reading time goes. Sizes only, never content.
     */
    private fun parts(request: BrainRequest): String {
        val tools = request.tools.sumOf { it.name.length + it.description.length + it.parameters.toString().length }
        val history = request.transcript.dropLast(1).sumOf(::characters)
        val message = request.transcript.lastOrNull()?.let(::characters) ?: 0
        return "system ${request.onDeviceSystem?.length ?: 0}, tools $tools, history $history, message $message chars"
    }

    private fun characters(message: ChatMessage): Int = when (message) {
        is ChatMessage.User -> message.modelText.length
        is ChatMessage.Model -> message.text.length + message.toolCalls.sumOf { it.name.length + it.arguments.toString().length }
        is ChatMessage.Tool -> message.results.sumOf { it.toolName.length + it.result.toString().length }
    }

    /**
     * Why a turn read its chat again (ADR-097): "new" when nothing was cached (the first turn, or after a load, an
     * unload, a failure or a cancel), else the parts of the request that differed from the most recently used
     * conversation. Only the parts' names, never their content.
     */
    private fun rebuilt(previous: CachedConversation?, key: ConversationKey, history: List<ChatMessage>): String {
        if (previous == null) return "new"
        val parts = listOfNotNull(
            "system".takeIf { previous.key.system != key.system },
            "tools".takeIf { previous.key.tools != key.tools },
            "sampling".takeIf { previous.key.sampling != key.sampling },
            "thinking".takeIf { previous.key.thinking != key.thinking },
            "history".takeIf { previous.history != history },
        )
        return "rebuilt (${parts.joinToString()})"
    }

    /**
     * The turn's totals, and how it ran (ADR-071). With the runtime's counters ([counters]), the token counts
     * and rates are the runtime's own. Without them they are estimates: what was written is the number of text
     * pieces, what was read is the rest of the context's growth since [before] (the size after the previous turn
     * in this conversation, 0 when new), and the times come from the clock around the first piece. The first
     * piece can arrive late, so those estimates still need S1's check against the counters (ENG-2).
     */
    private fun usage(tokens: Int, before: Int, pieces: Int, counters: TurnSpeed?, sent: Long, first: Long, done: Long): BrainEvent.Usage {
        val start = if (first > 0) first else done
        return BrainEvent.Usage(
            contextTokens = tokens,
            inputTokens = counters?.read ?: (tokens - before - pieces).coerceAtLeast(0),
            outputTokens = counters?.written ?: pieces,
            backend = backend,
            readMs = counters?.let { millisFor(it.read, it.readPerSecond) } ?: ((start - sent) / 1_000_000),
            writeMs = counters?.let { millisFor(it.written, it.writtenPerSecond) } ?: ((done - start) / 1_000_000),
            counted = counters != null,
        )
    }

    /** How long [tokens] took at the runtime's own [perSecond] rate, or null when it gave none. */
    private fun millisFor(tokens: Int, perSecond: Double): Long? = if (perSecond > 0) (tokens * 1_000 / perSecond).toLong() else null

    /** The runtime's counters for the turn that just finished, with the time to the first token measured here. */
    @OptIn(ExperimentalApi::class)
    private fun speed(conversation: Conversation, sent: Long, first: Long): TurnSpeed? {
        if (!engines.options.benchmark) return null
        val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull() ?: return null
        return TurnSpeed(
            read = info.lastPrefillTokenCount,
            readPerSecond = info.lastPrefillTokensPerSecond,
            firstTokenMs = if (first > 0) (first - sent) / 1_000_000 else -1,
            written = info.lastDecodeTokenCount,
            writtenPerSecond = info.lastDecodeTokensPerSecond,
        )
    }

    private suspend fun release(session: CachedConversation, finished: CompletableDeferred<Boolean>) {
        when (withTimeoutOrNull(CANCEL_GRACE_MS) { finished.await() }) {
            true -> {
                cached.addLast(session)
                while (cached.size > KEPT) cached.removeFirst().conversation.close()
            }
            false -> session.conversation.close()
            // The runtime hasn't confirmed the cancel yet. Close once it does, never mid-decode.
            null -> finished.invokeOnCompletion { session.conversation.close() }
        }
    }

    private data class ConversationKey(val system: String?, val tools: List<ToolSpec>, val sampling: Sampling, val thinking: Boolean)

    private class CachedConversation(
        val key: ConversationKey,
        val conversation: Conversation,
        @Volatile var history: List<ChatMessage>,
    ) {
        /** The context's size after the last turn, so the next can tell what it read. */
        @Volatile var tokens: Int = 0

        /** Whether the current turn kept this conversation or why it was built ([rebuilt]), for the debug speed line. */
        @Volatile var why: String = "new"
    }

    /**
     * Creates a conversation that drops channel content, the model's reasoning, from its KV cache, so the next turn
     * never reads it (ADR-077). LiteRT-LM reads the flag when a conversation is created, and the flag is process-wide.
     */
    @OptIn(ExperimentalApi::class)
    private fun <T> withoutThoughtsInCache(create: () -> T): T = synchronized(EngineHolder.FLAGS) {
        val before = ExperimentalFlags.filterChannelContentFromKvCache
        ExperimentalFlags.filterChannelContentFromKvCache = true
        try {
            create()
        } finally {
            ExperimentalFlags.filterChannelContentFromKvCache = before
        }
    }

    private companion object {
        const val CANCEL_GRACE_MS = 2_000L

        /** How many conversations stay cached per model ([cached]). */
        const val KEPT = 2

        /**
         * How long the runtime may say nothing before the turn fails. Well past a whole window's reading on a phone's
         * CPU (about 70 s for 4,096 tokens), and past LiteRT-LM's own two-minute task deadline, which it can miss silently.
         */
        const val STALL_MS = 180_000L
        const val STALL_CHECK_MS = 5_000L

        /** The channel Gemma 4 thinks in, as the thinking probe found (ADR-077). */
        const val THOUGHT = "thought"

        /** The most the model thinks, in tokens. With a short answer it stays within the 1,536-token reserve (ADR-040). */
        const val THINKING_BUDGET = 512

        /** What the model says for a recording with no words, as asked in [TRANSCRIBE] (S6: silence and noise). */
        const val NO_SPEECH = "[no speech]"

        /** The transcription prompt S6 measured: word for word, in the spoken language, with a marker for silence. */
        const val TRANSCRIBE = "Write down exactly what is said in this recording, word for word, in the language it is spoken in. " +
            "Reply with the words only. If no one speaks, reply with only: $NO_SPEECH"

        /** Room for the words of a 30-second piece, about 100 tokens, with a wide margin, so a loop can't run on. */
        const val TRANSCRIPT_TOKENS = 384
    }
}

/** The runtime went quiet in the middle of a turn, so the turn was given up (see [LiteRtLmBrain]). */
class ModelStalled : IllegalStateException("the model stopped responding, so this turn was stopped. Try again")
