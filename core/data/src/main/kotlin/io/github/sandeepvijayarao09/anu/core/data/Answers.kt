package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AnswerDetails
import io.github.sandeepvijayarao09.anu.core.agent.AnswerStore
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.router.Guard
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * One answer's details for "About this answer" (ADR-071), keyed by the transcript index of the message that
 * asked. The numbers are columns, so the Models page can read real use from them; the rest is JSON.
 */
@Entity(tableName = "answers", primaryKeys = ["conversationId", "turn"])
data class AnswerEntity(
    val conversationId: String,
    val turn: Int,
    val tier: String,
    val backend: String?,
    val firstWordMs: Long?,
    val totalMs: Long?,
    val readTokens: Int?,
    val readMs: Long?,
    val writtenTokens: Int?,
    val writeMs: Long?,
    val counted: Boolean,
    val atMillis: Long,
    /** The tier that tried first, the facts given by id, whether the summary was shown, and what tools did. */
    val detailsJson: String,
)

@Dao
interface AnswerDao {
    @Query("SELECT * FROM answers WHERE conversationId = :conversationId ORDER BY turn")
    suspend fun load(conversationId: String): List<AnswerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(answer: AnswerEntity)

    @Query("DELETE FROM answers WHERE conversationId = :conversationId AND turn >= :fromTurn")
    suspend fun deleteFrom(conversationId: String, fromTurn: Int)

    /** The tier of the newest answer, for warm start (ADR-074), or null before any. */
    @Query("SELECT tier FROM answers ORDER BY atMillis DESC LIMIT 1")
    suspend fun latestTier(): String?

    /** The latest answers from [tier] on [backend], newest first, for the Models page's real use (ADR-073). */
    @Query("SELECT * FROM answers WHERE tier = :tier AND backend = :backend AND firstWordMs IS NOT NULL ORDER BY atMillis DESC LIMIT :limit")
    suspend fun recent(tier: String, backend: String, limit: Int): List<AnswerEntity>
}

/**
 * Real use (G8, ENG-7, ADR-073): the middle of the last [limit] answers from [tier] on [backend], by first word,
 * reading and writing. The middle, not the mean, so one slow answer after a cold start doesn't skew it. Null when
 * there are none, as after the chat is reset.
 */
suspend fun AnswerDao.realUse(tier: Tier, backend: String, limit: Int = 20): Speed? {
    val rows = recent(tier.name, backend, limit)
    if (rows.isEmpty()) return null
    return Speed(
        firstWordMs = median(rows.mapNotNull { it.firstWordMs?.toDouble() })?.toLong(),
        readPerSecond = median(rows.mapNotNull { rate(it.readTokens, it.readMs) }),
        writePerSecond = median(rows.mapNotNull { rate(it.writtenTokens, it.writeMs) }),
        counted = rows.all { it.counted },
        atMillis = rows.first().atMillis,
        answers = rows.size,
    )
}

private fun rate(tokens: Int?, ms: Long?): Double? = if (tokens == null || tokens <= 0 || ms == null || ms <= 0) null else tokens * 1_000.0 / ms

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/** Answers' details in the encrypted database, beside the transcript they belong to (§14). */
class RoomAnswerStore(private val dao: AnswerDao, private val conversationId: String) : AnswerStore {

    override suspend fun load(): Map<Int, AnswerDetails> = dao.load(conversationId).associate { it.turn to AnswerCodec.decode(it) }

    override suspend fun save(turn: Int, details: AnswerDetails) = dao.put(AnswerCodec.encode(conversationId, turn, details))

    override suspend fun truncate(turn: Int) = dao.deleteFrom(conversationId, turn)
}

internal object AnswerCodec {
    fun encode(conversationId: String, turn: Int, details: AnswerDetails) = AnswerEntity(
        conversationId = conversationId,
        turn = turn,
        tier = details.tier.name,
        backend = details.backend,
        firstWordMs = details.firstWordMs,
        totalMs = details.totalMs,
        readTokens = details.read,
        readMs = details.readMs,
        writtenTokens = details.written,
        writeMs = details.writeMs,
        counted = details.counted,
        atMillis = details.atMillis,
        detailsJson = buildJsonObject {
            put("escalatedFrom", details.escalatedFrom?.name)
            details.guard?.let { put("guard", it.name) }
            if (details.cut) put("cut", true)
            if (details.direct) put("direct", true)
            details.thoughtMs?.let { put("thoughtMs", it) }
            details.heardMs?.let { put("heardMs", it) }
            put("factIds", JsonArray(details.factIds.map(::JsonPrimitive)))
            put("summaryShown", details.summaryShown)
            put(
                "actions",
                JsonArray(
                    details.actions.map { action ->
                        buildJsonObject {
                            put("tool", action.tool)
                            put("summary", action.summary)
                            put("status", action.status.name)
                            put("wrote", action.wrote)
                            action.reason?.let { put("reason", it) }
                            action.destination?.let { put("destination", it) }
                        }
                    },
                ),
            )
        }.toString(),
    )

    fun decode(row: AnswerEntity): AnswerDetails {
        val json = Json.parseToJsonElement(row.detailsJson).jsonObject
        return AnswerDetails(
            tier = Tier.valueOf(row.tier),
            backend = row.backend,
            escalatedFrom = json["escalatedFrom"]?.jsonPrimitive?.contentOrNull?.let(Tier::valueOf),
            factIds = json["factIds"]?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty(),
            summaryShown = json["summaryShown"]?.jsonPrimitive?.boolean ?: false,
            actions = json["actions"]?.jsonArray?.map { element -> action(element.jsonObject) }.orEmpty(),
            firstWordMs = row.firstWordMs,
            totalMs = row.totalMs,
            read = row.readTokens,
            readMs = row.readMs,
            written = row.writtenTokens,
            writeMs = row.writeMs,
            counted = row.counted,
            atMillis = row.atMillis,
            guard = json["guard"]?.jsonPrimitive?.contentOrNull?.let { name -> Guard.entries.firstOrNull { it.name == name } },
            cut = json["cut"]?.jsonPrimitive?.boolean ?: false,
            direct = json["direct"]?.jsonPrimitive?.boolean ?: false,
            thoughtMs = json["thoughtMs"]?.jsonPrimitive?.long,
            heardMs = json["heardMs"]?.jsonPrimitive?.long,
        )
    }

    private fun action(json: JsonObject) = ActionRecord(
        tool = json.getValue("tool").jsonPrimitive.content,
        summary = json.getValue("summary").jsonPrimitive.content,
        status = ActionRecord.Status.valueOf(json.getValue("status").jsonPrimitive.content),
        wrote = json["wrote"]?.jsonPrimitive?.boolean ?: false,
        reason = json["reason"]?.jsonPrimitive?.contentOrNull,
        destination = json["destination"]?.jsonPrimitive?.contentOrNull,
    )
}
