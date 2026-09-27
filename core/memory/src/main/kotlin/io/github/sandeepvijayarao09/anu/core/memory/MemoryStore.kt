package io.github.sandeepvijayarao09.anu.core.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where memory is kept (§8.1). `core:data` implements it on the encrypted database; only it touches
 * the database (§4.2). The pipeline owns all the rules; the store only records.
 */
interface MemoryStore {
    /** Runs [block] as one unit, so an error part-way leaves nothing half-changed. */
    suspend fun <T> atomically(block: suspend () -> T): T

    suspend fun addEvent(event: MemoryEvent): Long

    suspend fun events(ids: Collection<Long>): List<MemoryEvent>

    /** Oldest first. */
    suspend fun pending(limit: Int): List<MemoryEvent>

    suspend fun setStatus(id: Long, status: EventStatus, attempts: Int)

    /** Deletes the event and every provenance link to it. The pipeline decides what happens to the facts (I5). */
    suspend fun deleteEvent(id: Long)

    suspend fun activeFacts(): List<Fact>

    /** Facts in any status that list [eventId] as a source. */
    suspend fun factsFrom(eventId: Long): List<Fact>

    suspend fun factsSupersededBy(factId: Long): List<Fact>

    /** Inserts [fact] with its sources, ignoring its id. Returns the new id. */
    suspend fun insertFact(fact: Fact): Long

    /** Replaces the fact's fields and its sources. */
    suspend fun updateFact(fact: Fact)

    suspend fun deleteFact(id: Long)

    /** Facts the user deleted are remembered only as a fingerprint, so they aren't learned again (§8.4). */
    suspend fun isRejected(fingerprint: String): Boolean

    suspend fun reject(fingerprint: String)

    /** Clears a fingerprint, when the user states that fact again themself (P5). */
    suspend fun forgive(fingerprint: String)

    /** The newest version of each section. */
    suspend fun latestProfile(): List<ProfileVersion>

    suspend fun saveProfile(version: ProfileVersion)

    fun observeFacts(): Flow<List<Fact>>

    fun observePendingCount(): Flow<Int>

    /** Vectors of active facts, made by [model], by fact id (ADR-055). */
    suspend fun vectors(model: String): Map<Long, FloatArray>

    /** Keeps each fact's vector for [model]. A fact has one vector per model; deleting it deletes them. */
    suspend fun putVectors(model: String, vectors: Map<Long, FloatArray>)
}

/** A [MemoryStore] in memory, for tests and previews. */
class InMemoryMemoryStore : MemoryStore {
    private data class State(
        val events: Map<Long, MemoryEvent> = emptyMap(),
        val facts: Map<Long, Fact> = emptyMap(),
        val rejected: Set<String> = emptySet(),
        val profile: List<ProfileVersion> = emptyList(),
        val vectors: Map<Pair<String, Long>, FloatArray> = emptyMap(),
        val nextId: Long = 1,
    )

    private val state = MutableStateFlow(State())
    private val lock = Mutex()

    override suspend fun <T> atomically(block: suspend () -> T): T = lock.withLock { block() }

    override suspend fun addEvent(event: MemoryEvent): Long {
        var id = 0L
        update { id = it.nextId; it.copy(events = it.events + (id to event.copy(id = id)), nextId = id + 1) }
        return id
    }

    override suspend fun events(ids: Collection<Long>) = ids.mapNotNull { state.value.events[it] }

    override suspend fun pending(limit: Int) =
        state.value.events.values.filter { it.status == EventStatus.PENDING }.sortedBy { it.id }.take(limit)

    override suspend fun setStatus(id: Long, status: EventStatus, attempts: Int) = update { s ->
        val event = s.events[id] ?: return@update s
        s.copy(events = s.events + (id to event.copy(status = status, attempts = attempts)))
    }

    override suspend fun deleteEvent(id: Long) = update { s ->
        s.copy(events = s.events - id, facts = s.facts.mapValues { (_, f) -> f.copy(sources = f.sources - id) })
    }

    override suspend fun activeFacts() = state.value.facts.values.filter { it.status == FactStatus.ACTIVE }.sortedBy { it.id }

    override suspend fun factsFrom(eventId: Long) = state.value.facts.values.filter { eventId in it.sources }.sortedBy { it.id }

    override suspend fun factsSupersededBy(factId: Long) = state.value.facts.values.filter { it.supersededBy == factId }

    override suspend fun insertFact(fact: Fact): Long {
        var id = 0L
        update { id = it.nextId; it.copy(facts = it.facts + (id to fact.copy(id = id)), nextId = id + 1) }
        return id
    }

    override suspend fun updateFact(fact: Fact) = update { it.copy(facts = it.facts + (fact.id to fact)) }

    override suspend fun deleteFact(id: Long) = update { s -> s.copy(facts = s.facts - id, vectors = s.vectors.filterKeys { it.second != id }) }

    override suspend fun isRejected(fingerprint: String) = fingerprint in state.value.rejected

    override suspend fun reject(fingerprint: String) = update { it.copy(rejected = it.rejected + fingerprint) }

    override suspend fun forgive(fingerprint: String) = update { it.copy(rejected = it.rejected - fingerprint) }

    override suspend fun latestProfile() =
        state.value.profile.groupBy { it.section }.values.map { versions -> versions.maxBy { it.version } }

    override suspend fun saveProfile(version: ProfileVersion) = update { it.copy(profile = it.profile + version) }

    override fun observeFacts(): Flow<List<Fact>> =
        state.map { s -> s.facts.values.filter { it.status == FactStatus.ACTIVE }.sortedBy { it.id } }

    override fun observePendingCount(): Flow<Int> = state.map { s -> s.events.values.count { it.status == EventStatus.PENDING } }

    override suspend fun vectors(model: String): Map<Long, FloatArray> = state.value.let { s ->
        s.vectors.filterKeys { (m, id) -> m == model && s.facts[id]?.status == FactStatus.ACTIVE }.mapKeys { it.key.second }
    }

    override suspend fun putVectors(model: String, vectors: Map<Long, FloatArray>) =
        update { s -> s.copy(vectors = s.vectors + vectors.mapKeys { (id, _) -> model to id }) }

    /** Every event, for tests. */
    fun allEvents(): List<MemoryEvent> = state.value.events.values.sortedBy { it.id }

    /** Every fact, superseded ones too, for tests. */
    fun allFacts(): List<Fact> = state.value.facts.values.sortedBy { it.id }

    fun allProfileVersions(): List<ProfileVersion> = state.value.profile

    private fun update(change: (State) -> State) {
        synchronized(this) { state.value = change(state.value) }
    }
}
