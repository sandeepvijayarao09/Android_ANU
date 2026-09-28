package io.github.sandeepvijayarao09.anu.brain.litertlm

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
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
import io.github.sandeepvijayarao09.anu.core.model.onDeviceSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
data class TurnSpeed(val read: Int, val readPerSecond: Double, val firstTokenMs: Long, val written: Int, val writtenPerSecond: Double)

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
) : Brain {

    /** One generation at a time per model. It also guards [cached]. */
    private val generation = Mutex()
    private var cached: CachedConversation? = null

    override fun stream(request: BrainRequest): Flow<BrainEvent> = callbackFlow {
        generation.lock()
        // Completes when the runtime has finished the turn: true on success, false on error or cancel.
        val finished = CompletableDeferred<Boolean>()
        var session: CachedConversation? = null
        try {
            val active = acquire(engines.get(), request)
            session = active
            val reply = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            val sent = AtomicLong()
            val first = AtomicLong()
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    first.compareAndSet(0, System.nanoTime())
                    val delta = message.text()
                    if (delta.isNotEmpty()) {
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
                    active.history = request.transcript + ChatMessage.Model(reply.toString(), calls.toList())
                    onTurn?.let { report -> speed(active.conversation, sent.get(), first.get())?.let(report) }
                    trySend(BrainEvent.Usage(contextTokens = active.conversation.getTokenCount()))
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
            awaitClose {
                if (!finished.isCompleted) runCatching { active.conversation.cancelProcess() }
            }
        } finally {
            withContext(NonCancellable) {
                session?.let { release(it, finished) }
                generation.unlock()
            }
        }
    }.flowOn(dispatcher)

    /** Frees the cached conversation, for example before the residency policy unloads the model. */
    suspend fun reset() = generation.withLock {
        cached?.conversation?.close()
        cached = null
    }

    private fun acquire(engine: Engine, request: BrainRequest): CachedConversation {
        val system = request.onDeviceSystem
        val key = ConversationKey(system, request.tools, request.sampling)
        val history = request.transcript.dropLast(1)
        val previous = cached
        cached = null
        if (previous != null && previous.key == key && previous.history == history) return previous
        previous?.conversation?.close()
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = system?.let { Contents.of(it) },
                initialMessages = history.indices.map(request::messageAt),
                tools = request.tools.map { tool(DeclaredTool(it)) },
                samplerConfig = request.sampling.toLiteRt(),
                automaticToolCalling = false,
                enableResponseFormat = true,
            ),
        )
        return CachedConversation(key, conversation, history)
    }

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
            true -> cached = session
            false -> session.conversation.close()
            // The runtime hasn't confirmed the cancel yet. Close once it does, never mid-decode.
            null -> finished.invokeOnCompletion { session.conversation.close() }
        }
    }

    private data class ConversationKey(val system: String?, val tools: List<ToolSpec>, val sampling: Sampling)

    private class CachedConversation(
        val key: ConversationKey,
        val conversation: Conversation,
        @Volatile var history: List<ChatMessage>,
    )

    private companion object {
        const val CANCEL_GRACE_MS = 2_000L
    }
}
