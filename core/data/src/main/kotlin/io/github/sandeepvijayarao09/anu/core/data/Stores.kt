package io.github.sandeepvijayarao09.anu.core.data

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolAudit
import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.agent.ToolResultStore
import io.github.sandeepvijayarao09.anu.core.agent.TranscriptStore
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PiiKind
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.vault.VaultAudit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/** A conversation's transcript in the encrypted database (ADR-018). */
class RoomTranscriptStore(
    private val dao: TranscriptDao,
    private val conversationId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : TranscriptStore {

    override suspend fun load(): List<ChatMessage> = dao.load(conversationId).map(MessageCodec::decode)

    override suspend fun append(messages: List<ChatMessage>) {
        val start = dao.nextSeq(conversationId)
        val now = clock()
        dao.insert(messages.mapIndexed { i, message -> MessageCodec.encode(conversationId, start + i, message, now) })
    }

    override suspend fun truncate(size: Int) = dao.deleteFrom(conversationId, fromSeq = size)
}

/** The Privacy Ledger in the encrypted database (I3). Entries survive restarts. */
class RoomPrivacyLedger(private val dao: LedgerDao, scope: CoroutineScope) : PrivacyLedger {

    override val entries: StateFlow<List<LedgerEntry>> =
        dao.all().map { rows -> rows.map(::decode) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun record(entry: LedgerEntry) = dao.insert(
        LedgerEntity(
            atMillis = entry.at.toEpochMilli(),
            destination = entry.destination,
            purpose = entry.purpose,
            dataClasses = entry.dataClasses.map { it.name }.sorted().joinToString(","),
            redactedJson = JsonObject(entry.redacted.entries.associate { (kind, count) -> kind.name to JsonPrimitive(count) }).toString(),
            characters = entry.characters,
        ),
    )

    private fun decode(row: LedgerEntity) = LedgerEntry(
        at = Instant.ofEpochMilli(row.atMillis),
        destination = row.destination,
        purpose = row.purpose,
        dataClasses = row.dataClasses.split(",").filter(String::isNotEmpty).mapTo(mutableSetOf(), DataClass::valueOf),
        redacted = Json.parseToJsonElement(row.redactedJson).jsonObject.entries
            .associate { (kind, count) -> PiiKind.valueOf(kind) to count.jsonPrimitive.int },
        characters = row.characters,
    )
}

data class VaultUse(val itemId: String, val caller: String, val host: String, val at: Instant)

/** Keeps the vault's audit trail in the encrypted database, so the user can see every use (§11). */
class RoomVaultAudit(private val dao: VaultAuditDao) : VaultAudit {

    override fun used(ref: SecretRef, caller: String, host: String, atMillis: Long) =
        dao.insertBlocking(VaultAuditEntity(itemId = ref.id, caller = caller, host = host, atMillis = atMillis))

    fun recent(limit: Int = 50): Flow<List<VaultUse>> = dao.recent(limit).map { rows ->
        rows.map { VaultUse(it.itemId, it.caller, it.host, Instant.ofEpochMilli(it.atMillis)) }
    }
}

/** The Tool Broker's audit trail in the encrypted database (§7.2). */
class RoomToolAudit(private val dao: ToolAuditDao) : ToolAudit {
    override suspend fun record(entry: ToolAuditEntry) = dao.insert(
        ToolAuditEntity(
            runId = entry.runId,
            toolName = entry.toolName,
            risk = entry.risk.name,
            summary = entry.summary,
            outcome = entry.outcome,
            approved = entry.approved,
            atMillis = entry.atMillis,
        ),
    )

    fun recent(limit: Int = 50): Flow<List<ToolAuditEntry>> = dao.recent(limit).map { rows ->
        rows.map { ToolAuditEntry(it.runId, it.toolName, RiskTier.valueOf(it.risk), it.summary, it.outcome, it.approved, it.atMillis) }
    }
}

/** Idempotent results that survive a restart, so a resumed turn never repeats a side effect (§7.2, §18). */
class RoomToolResultStore(private val dao: ToolResultDao, private val clock: () -> Long = System::currentTimeMillis) : ToolResultStore {
    override suspend fun get(key: String): JsonElement? = dao.get(key)?.let(Json::parseToJsonElement)

    override suspend fun put(key: String, result: JsonElement) = dao.put(ToolResultEntity(key, result.toString(), clock()))
}

/** Maps transcript messages to rows. Tool calls keep their provider signature (ADR-022). */
internal object MessageCodec {
    fun encode(conversationId: String, seq: Int, message: ChatMessage, now: Long): MessageEntity = when (message) {
        is ChatMessage.User -> MessageEntity(conversationId, seq, "user", message.text, null, null, now)
        is ChatMessage.Model -> MessageEntity(
            conversationId, seq, "model", message.text,
            toolCallsJson = message.toolCalls.takeIf { it.isNotEmpty() }?.let { calls ->
                JsonArray(
                    calls.map { call ->
                        buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.arguments)
                            call.signature?.let { put("signature", it) }
                        }
                    },
                ).toString()
            },
            toolResultsJson = null,
            createdAtMillis = now,
        )
        is ChatMessage.Tool -> MessageEntity(
            conversationId, seq, "tool", text = "",
            toolCallsJson = null,
            toolResultsJson = JsonArray(
                message.results.map { result -> buildJsonObject { put("toolName", result.toolName); put("result", result.result) } },
            ).toString(),
            createdAtMillis = now,
        )
    }

    fun decode(row: MessageEntity): ChatMessage = when (row.role) {
        "user" -> ChatMessage.User(row.text)
        "model" -> ChatMessage.Model(
            row.text,
            row.toolCallsJson?.let { json ->
                Json.parseToJsonElement(json).jsonArray.map { element ->
                    val call = element.jsonObject
                    ToolCall(
                        name = call.getValue("name").jsonPrimitive.content,
                        arguments = call.getValue("arguments").jsonObject,
                        signature = call["signature"]?.jsonPrimitive?.content,
                    )
                }
            }.orEmpty(),
        )
        "tool" -> ChatMessage.Tool(
            Json.parseToJsonElement(row.toolResultsJson ?: "[]").jsonArray.map { element ->
                val result = element.jsonObject
                ToolResult(result.getValue("toolName").jsonPrimitive.content, result.getValue("result"))
            },
        )
        else -> error("Unknown message role ${row.role}")
    }
}
