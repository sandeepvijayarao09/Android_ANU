package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.withTransaction
import io.github.sandeepvijayarao09.anu.core.memory.EventSource
import io.github.sandeepvijayarao09.anu.core.memory.EventStatus
import io.github.sandeepvijayarao09.anu.core.memory.Fact
import io.github.sandeepvijayarao09.anu.core.memory.FactOrigin
import io.github.sandeepvijayarao09.anu.core.memory.FactStatus
import io.github.sandeepvijayarao09.anu.core.memory.MemoryEvent
import io.github.sandeepvijayarao09.anu.core.memory.MemoryStore
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import io.github.sandeepvijayarao09.anu.core.memory.ProfileVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** An append-only memory event (§8.1). */
@Entity(tableName = "memory_events", indices = [Index("status")])
data class MemoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val source: String,
    val content: String,
    val ref: String?,
    val status: String,
    val attempts: Int,
)

/** A fact (§8.1). Its provenance lives in [FactSourceEntity] (I4). */
@Entity(tableName = "facts", indices = [Index("status"), Index("subject", "predicate"), Index("supersededBy")])
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val predicate: String,
    val value: String,
    val section: String,
    val confidence: Double,
    val origin: String,
    val status: String,
    val sensitive: Boolean,
    val evidence: String?,
    val firstSeenMillis: Long,
    val lastConfirmedMillis: Long,
    val supersededBy: Long?,
)

/** Which events a fact came from (I4). Deleting either side deletes the link, in code (I5). */
@Entity(tableName = "fact_sources", primaryKeys = ["factId", "eventId"], indices = [Index("eventId")])
data class FactSourceEntity(val factId: Long, val eventId: Long)

/** A fact the user deleted, kept only as a hash so it isn't learned again (§8.4). */
@Entity(tableName = "rejected_facts")
data class RejectedFactEntity(@PrimaryKey val fingerprint: String, val atMillis: Long)

/** One version of a profile section (§8.1). */
@Entity(tableName = "profile_versions", primaryKeys = ["section", "version"])
data class ProfileVersionEntity(val section: String, val version: Int, val text: String, val factIds: String, val atMillis: Long)

data class FactWithSources(
    @Embedded val fact: FactEntity,
    @Relation(parentColumn = "id", entityColumn = "factId", entity = FactSourceEntity::class, projection = ["eventId"])
    val sources: List<Long>,
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun insertEvent(event: MemoryEventEntity): Long

    @Query("SELECT * FROM memory_events WHERE id IN (:ids)")
    suspend fun events(ids: List<Long>): List<MemoryEventEntity>

    @Query("SELECT * FROM memory_events WHERE status = 'PENDING' ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<MemoryEventEntity>

    @Query("UPDATE memory_events SET status = :status, attempts = :attempts WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, attempts: Int)

    @Query("DELETE FROM memory_events WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    @Query("DELETE FROM fact_sources WHERE eventId = :eventId")
    suspend fun deleteLinksToEvent(eventId: Long)

    @Query("SELECT COUNT(*) FROM memory_events WHERE status = 'PENDING'")
    fun pendingCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM facts WHERE status = 'ACTIVE' ORDER BY id")
    suspend fun activeFacts(): List<FactWithSources>

    @Transaction
    @Query("SELECT * FROM facts WHERE status = 'ACTIVE' ORDER BY id")
    fun observeActiveFacts(): Flow<List<FactWithSources>>

    @Transaction
    @Query("SELECT * FROM facts WHERE id IN (SELECT factId FROM fact_sources WHERE eventId = :eventId) ORDER BY id")
    suspend fun factsFrom(eventId: Long): List<FactWithSources>

    @Transaction
    @Query("SELECT * FROM facts WHERE supersededBy = :factId")
    suspend fun supersededBy(factId: Long): List<FactWithSources>

    @Insert
    suspend fun insertFact(fact: FactEntity): Long

    @Update
    suspend fun updateFact(fact: FactEntity)

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteFact(id: Long)

    @Query("DELETE FROM fact_sources WHERE factId = :factId")
    suspend fun deleteLinksOfFact(factId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<FactSourceEntity>)

    @Query("SELECT COUNT(*) FROM rejected_facts WHERE fingerprint = :fingerprint")
    suspend fun rejectedCount(fingerprint: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun reject(entry: RejectedFactEntity)

    @Query("SELECT * FROM profile_versions p WHERE version = (SELECT MAX(version) FROM profile_versions WHERE section = p.section)")
    suspend fun latestProfile(): List<ProfileVersionEntity>

    @Insert
    suspend fun insertProfile(version: ProfileVersionEntity)
}

/** Memory in the encrypted database (§8.1, §14). Only `core:data` touches the database (§4.2). */
class RoomMemoryStore(private val db: AnuDatabase, private val clock: () -> Long = System::currentTimeMillis) : MemoryStore {
    private val dao = db.memory()

    override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun addEvent(event: MemoryEvent): Long = dao.insertEvent(
        MemoryEventEntity(
            atMillis = event.at.toEpochMilli(), source = event.source.name, content = event.content, ref = event.ref,
            status = event.status.name, attempts = event.attempts,
        ),
    )

    override suspend fun events(ids: Collection<Long>): List<MemoryEvent> =
        if (ids.isEmpty()) emptyList() else ids.chunked(500).flatMap { dao.events(it) }.map(::event)

    override suspend fun pending(limit: Int) = dao.pending(limit).map(::event)

    override suspend fun setStatus(id: Long, status: EventStatus, attempts: Int) = dao.setStatus(id, status.name, attempts)

    override suspend fun deleteEvent(id: Long) = db.withTransaction {
        dao.deleteLinksToEvent(id)
        dao.deleteEvent(id)
    }

    override suspend fun activeFacts() = dao.activeFacts().map(::fact)

    override suspend fun factsFrom(eventId: Long) = dao.factsFrom(eventId).map(::fact)

    override suspend fun factsSupersededBy(factId: Long) = dao.supersededBy(factId).map(::fact)

    override suspend fun insertFact(fact: Fact): Long = db.withTransaction {
        val id = dao.insertFact(entity(fact).copy(id = 0))
        dao.insertLinks(fact.sources.map { FactSourceEntity(id, it) })
        id
    }

    override suspend fun updateFact(fact: Fact) = db.withTransaction {
        dao.updateFact(entity(fact))
        dao.deleteLinksOfFact(fact.id)
        dao.insertLinks(fact.sources.map { FactSourceEntity(fact.id, it) })
    }

    override suspend fun deleteFact(id: Long) = db.withTransaction {
        dao.deleteLinksOfFact(id)
        dao.deleteFact(id)
    }

    override suspend fun isRejected(fingerprint: String) = dao.rejectedCount(fingerprint) > 0

    override suspend fun reject(fingerprint: String) = dao.reject(RejectedFactEntity(fingerprint, clock()))

    override suspend fun latestProfile() = dao.latestProfile().map { row ->
        ProfileVersion(
            section = ProfileSection.valueOf(row.section),
            version = row.version,
            text = row.text,
            factIds = row.factIds.split(",").filter(String::isNotEmpty).map(String::toLong),
            at = Instant.ofEpochMilli(row.atMillis),
        )
    }

    override suspend fun saveProfile(version: ProfileVersion) = dao.insertProfile(
        ProfileVersionEntity(version.section.name, version.version, version.text, version.factIds.joinToString(","), version.at.toEpochMilli()),
    )

    override fun observeFacts(): Flow<List<Fact>> = dao.observeActiveFacts().map { rows -> rows.map(::fact) }

    override fun observePendingCount(): Flow<Int> = dao.pendingCount()

    private fun event(row: MemoryEventEntity) = MemoryEvent(
        id = row.id, at = Instant.ofEpochMilli(row.atMillis), source = EventSource.valueOf(row.source), content = row.content,
        ref = row.ref, status = EventStatus.valueOf(row.status), attempts = row.attempts,
    )

    private fun fact(row: FactWithSources) = with(row.fact) {
        Fact(
            id = id, subject = subject, predicate = predicate, value = value, section = ProfileSection.valueOf(section),
            confidence = confidence, origin = FactOrigin.valueOf(origin), status = FactStatus.valueOf(status), sensitive = sensitive,
            evidence = evidence, firstSeen = Instant.ofEpochMilli(firstSeenMillis), lastConfirmed = Instant.ofEpochMilli(lastConfirmedMillis),
            supersededBy = supersededBy, sources = row.sources.sorted(),
        )
    }

    private fun entity(fact: Fact) = FactEntity(
        id = fact.id, subject = fact.subject, predicate = fact.predicate, value = fact.value, section = fact.section.name,
        confidence = fact.confidence, origin = fact.origin.name, status = fact.status.name, sensitive = fact.sensitive,
        evidence = fact.evidence, firstSeenMillis = fact.firstSeen.toEpochMilli(), lastConfirmedMillis = fact.lastConfirmed.toEpochMilli(),
        supersededBy = fact.supersededBy,
    )
}
