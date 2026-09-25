package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.security.PiiDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A plain chat conversation with no tools (Phase 1). Its transcript is persisted through [store] and
 * is the source of truth (ADR-018). A turn is saved only when the cascade completes, so a failed,
 * cancelled or consent-blocked turn leaves no trace.
 */
class ChatSession(
    private val cascade: Cascade,
    private val store: TranscriptStore = InMemoryTranscriptStore(),
    private val system: String = DEFAULT_SYSTEM,
) {
    private val lock = Mutex()
    private var transcript: MutableList<ChatMessage>? = null

    /** The saved conversation, loaded from [store] on first use. */
    suspend fun history(): List<ChatMessage> = lock.withLock { loaded().toList() }

    /** Sends [text]. Use [pinned] for "Retry with Pro", and [cloudConsented] after the user approves cloud use. */
    fun send(text: String, device: DeviceState, pinned: Tier? = null, cloudConsented: Boolean = false): Flow<CascadeEvent> = flow {
        lock.withLock {
            val transcript = loaded()
            val turn = transcript + ChatMessage.User(text)
            val route = RouteRequest(
                text = text,
                skill = Skill.CHAT,
                dataClasses = dataClassesOf(turn),
                promptTokens = estimateTokens(system, turn),
                pinnedTier = pinned,
                cloudConsented = cloudConsented,
            )
            cascade.run(route, device, BrainRequest(system = system, transcript = turn)).collect { event ->
                if (event is CascadeEvent.Completed) {
                    val exchange = listOf(ChatMessage.User(text), ChatMessage.Model(event.text, event.toolCalls))
                    store.append(exchange)
                    transcript += exchange
                }
                emit(event)
            }
        }
    }

    /** Removes the last exchange so it can be sent again, for example on a different tier. Returns its text. */
    suspend fun takeBackLast(): String? = lock.withLock {
        val transcript = loaded()
        val userIndex = transcript.indexOfLast { it is ChatMessage.User }
        if (userIndex < 0) return null
        val text = (transcript[userIndex] as ChatMessage.User).text
        store.truncate(userIndex)
        while (transcript.size > userIndex) transcript.removeAt(transcript.lastIndex)
        text
    }

    /** Callers hold [lock]. */
    private suspend fun loaded(): MutableList<ChatMessage> = transcript ?: store.load().toMutableList().also { transcript = it }

    companion object {
        const val DEFAULT_SYSTEM =
            "You are Anu, a private assistant running on the user's phone. Be concise, warm and accurate. " +
                "If you are unsure, say so."

        /** Chat text may hold anything personal, so it is always at least S1. S2 is added when detected. */
        fun dataClassesOf(turn: List<ChatMessage>): Set<DataClass> =
            setOf(DataClass.PERSONAL) + turn.filterIsInstance<ChatMessage.User>().flatMap { PiiDetector.dataClasses(it.text) }

        /** About four characters per token. Real counts come from the runtime once S1 calibrates budgets. */
        fun estimateTokens(system: String, turn: List<ChatMessage>): Int =
            (system.length + turn.sumOf { message ->
                when (message) {
                    is ChatMessage.User -> message.text.length
                    is ChatMessage.Model -> message.text.length
                    is ChatMessage.Tool -> message.results.sumOf { it.result.toString().length }
                }
            }) / 4 + 1
    }
}
