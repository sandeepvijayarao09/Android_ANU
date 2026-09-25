package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ChatMessage

/**
 * Where a conversation's transcript is kept. The transcript is the source of truth (ADR-018).
 * `core:data` provides the encrypted implementation; [InMemoryTranscriptStore] serves tests and previews.
 */
interface TranscriptStore {
    suspend fun load(): List<ChatMessage>

    suspend fun append(messages: List<ChatMessage>)

    /** Keeps only the first [size] messages. */
    suspend fun truncate(size: Int)
}

class InMemoryTranscriptStore(initial: List<ChatMessage> = emptyList()) : TranscriptStore {
    private val messages = initial.toMutableList()

    override suspend fun load(): List<ChatMessage> = messages.toList()

    override suspend fun append(messages: List<ChatMessage>) {
        this.messages += messages
    }

    override suspend fun truncate(size: Int) {
        while (messages.size > size) messages.removeAt(messages.lastIndex)
    }
}
