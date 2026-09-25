package io.github.sandeepvijayarao09.anu.brain.litertlm

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
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
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
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
                active.conversation.sendMessageAsync(
                    request.transcript.last().toLiteRt(),
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
        val key = ConversationKey(request.system, request.tools, request.sampling)
        val history = request.transcript.dropLast(1)
        val previous = cached
        cached = null
        if (previous != null && previous.key == key && previous.history == history) return previous
        previous?.conversation?.close()
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = request.system?.let { Contents.of(it) },
                initialMessages = history.map { it.toLiteRt() },
                tools = request.tools.map { tool(DeclaredTool(it)) },
                samplerConfig = request.sampling.toLiteRt(),
                automaticToolCalling = false,
                enableResponseFormat = true,
            ),
        )
        return CachedConversation(key, conversation, history)
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
