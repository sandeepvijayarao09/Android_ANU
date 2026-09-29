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

/**
 * Where attached photos are kept (G2, ADR-075): on the phone, in Anu's encrypted store, as long as the message that
 * carries them. The transcript refers to them by id.
 */
interface PhotoStore {
    suspend fun save(bytes: ByteArray): Long

    /** The photos among [ids] that are still kept. */
    suspend fun load(ids: Collection<Long>): Map<Long, ByteArray>

    suspend fun delete(ids: Collection<Long>)
}

class InMemoryPhotoStore : PhotoStore {
    private val photos = mutableMapOf<Long, ByteArray>()
    private var next = 1L

    override suspend fun save(bytes: ByteArray): Long = next++.also { photos[it] = bytes }

    override suspend fun load(ids: Collection<Long>): Map<Long, ByteArray> = ids.mapNotNull { id -> photos[id]?.let { id to it } }.toMap()

    override suspend fun delete(ids: Collection<Long>) {
        ids.forEach(photos::remove)
    }
}
