package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PiiKind
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Store logic against Room's real SQL, on the JVM through Robolectric. Encryption itself needs
 * SQLCipher's native library, so it is checked on a device by `EncryptedDatabaseTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomStoresTest {

    private lateinit var db: AnuDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AnuDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private val transcript = listOf(
        ChatMessage.User("Email Maya the plan"),
        ChatMessage.Model(
            "",
            listOf(ToolCall("email_send", buildJsonObject { put("to", "maya@example.com") }, signature = "sig-1")),
        ),
        ChatMessage.Tool(listOf(ToolResult("email_send", buildJsonObject { put("status", "sent") }))),
        ChatMessage.Model("Sent."),
    )

    @Test
    fun `every kind of message round-trips, including tool calls and their signature`() = runBlocking {
        val store = RoomTranscriptStore(db.transcripts(), "main")
        store.append(transcript)
        assertEquals(transcript, store.load())
    }

    @Test
    fun `truncating then appending keeps the order intact`() = runBlocking {
        val store = RoomTranscriptStore(db.transcripts(), "main")
        store.append(transcript)
        store.truncate(1)
        store.append(listOf(ChatMessage.Model("Retried on Pro.")))
        assertEquals(listOf(ChatMessage.User("Email Maya the plan"), ChatMessage.Model("Retried on Pro.")), store.load())
    }

    @Test
    fun `conversations are kept apart`() = runBlocking {
        RoomTranscriptStore(db.transcripts(), "a").append(listOf(ChatMessage.User("in a")))
        RoomTranscriptStore(db.transcripts(), "b").append(listOf(ChatMessage.User("in b")))
        assertEquals(listOf(ChatMessage.User("in a")), RoomTranscriptStore(db.transcripts(), "a").load())
    }

    @Test
    fun `the ledger persists entries and streams them in order`() = runBlocking {
        val ledger = RoomPrivacyLedger(db.ledger(), scope)
        val entry = LedgerEntry(
            at = Instant.ofEpochMilli(1_790_000_000_000),
            destination = "generativelanguage.googleapis.com",
            purpose = "chat",
            dataClasses = setOf(DataClass.PERSONAL, DataClass.SENSITIVE),
            redacted = mapOf(PiiKind.EMAIL to 2, PiiKind.CARD to 1),
            characters = 420,
        )
        ledger.record(entry)
        ledger.record(entry.copy(purpose = "email"))
        val entries = withTimeout(5_000) { ledger.entries.first { it.size == 2 } }
        assertEquals(listOf(entry, entry.copy(purpose = "email")), entries)

        // A new ledger over the same database sees them: they survive a restart.
        val reopened = RoomPrivacyLedger(db.ledger(), scope)
        assertEquals(2, withTimeout(5_000) { reopened.entries.first { it.isNotEmpty() } }.size)
    }

    @Test
    fun `tool audit and idempotent results persist`() = runBlocking {
        val audit = RoomToolAudit(db.toolAudit())
        val entry = io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry(
            "run-1", "calendar_create_event", io.github.sandeepvijayarao09.anu.core.agent.RiskTier.WRITE_EXTERNAL,
            "Add \"Design review\" and invite maya@example.com", "ok", approved = true, atMillis = 5,
        )
        audit.record(entry)
        assertEquals(listOf(entry), audit.recent().first())

        val results = RoomToolResultStore(db.toolResults())
        val result = buildJsonObject { put("eventId", 7) }
        results.put("key-1", result)
        results.put("key-1", buildJsonObject { put("eventId", 8) }) // the first result wins: a replay never changes it
        assertEquals(result, results.get("key-1"))
        assertEquals(null, results.get("missing"))
    }

    @Test
    fun `the vault audit records who used which item where, and never a value`() = runBlocking {
        val audit = RoomVaultAudit(db.vaultAudit())
        // The vault reports uses from OkHttp's network thread, never the main thread.
        withContext(Dispatchers.IO) {
            audit.used(SecretRef("11111111-2222-3333-4444-555555555555"), "brain:gemini", "generativelanguage.googleapis.com", 1_000)
        }
        val use = audit.recent().first().single()
        assertEquals("brain:gemini", use.caller)
        assertEquals("generativelanguage.googleapis.com", use.host)
        assertFalse(VaultAuditEntity::class.java.declaredFields.any { it.name.contains("secret", ignoreCase = true) })
    }
}
