package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AnswerDetails
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Document
import io.github.sandeepvijayarao09.anu.core.model.Photo
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.router.Tier
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
    fun `a message's PDF round-trips as its name, size and text, and a scan's as pictures`() = runBlocking {
        val store = RoomTranscriptStore(db.transcripts(), "main")
        val notes = ChatMessage.User("Rent?", document = Document("Lease.pdf", pages = 54, read = 20, text = "Page 3: Rent is \$1,850.", whole = false))
        val whole = ChatMessage.User("", document = Document("Menu.pdf", pages = 1, read = 1, text = "Soup 6", whole = true))
        val scan = ChatMessage.User("What is this?", listOf(Photo(4), Photo(5)), document = Document("Scan.pdf", pages = 6, read = 2, text = "", whole = false, pictures = true))
        store.append(listOf(notes, ChatMessage.Model("\$1,850."), whole, ChatMessage.Model("Soup."), scan))
        assertEquals(listOf(notes, whole, scan), store.load().filterIsInstance<ChatMessage.User>())
    }

    @Test
    fun `what a tool result holds from others round-trips, and older rows say it wasn't recorded`() = runBlocking {
        val store = RoomTranscriptStore(db.transcripts(), "main")
        val results = ChatMessage.Tool(
            listOf(
                ToolResult("email_search", buildJsonObject { put("items", "Lunch?") }, UntrustedContent.EMAIL),
                ToolResult("calendar_create_event", buildJsonObject { put("id", "e1") }),
            ),
        )
        store.append(listOf(ChatMessage.User("Lunch?"), results))
        assertEquals(results, store.load().last())

        // A row written before the field existed.
        db.transcripts().insert(
            listOf(MessageEntity("old", 0, "tool", "", null, "[{\"toolName\":\"email_search\",\"result\":{\"items\":\"Hi\"}}]", 0)),
        )
        val old = (RoomTranscriptStore(db.transcripts(), "old").load().single() as ChatMessage.Tool).results.single()
        assertEquals(null, old.untrusted)
        assertFalse(old.untrustedKnown)
    }

    @Test
    fun `answers' details round-trip, and truncating removes them from a turn on`() = runBlocking {
        val store = RoomAnswerStore(db.answers(), "main")
        val first = AnswerDetails(
            tier = Tier.PRO, backend = "GPU", escalatedFrom = Tier.BASE, factIds = listOf(7L, 9L), summaryShown = true,
            actions = listOf(ActionRecord("mcp_notes_search", "Search notes", ActionRecord.Status.DONE, wrote = false, destination = "notes.example.com")),
            firstWordMs = 1_200, totalMs = 4_000, read = 271, readMs = 900, written = 20, writeMs = 2_800, counted = true, atMillis = 5,
            agents = listOf(
                io.github.sandeepvijayarao09.anu.core.agent.AgentStep("anu", 2, emptyList()),
                io.github.sandeepvijayarao09.anu.core.agent.AgentStep("calendar_agent", 2, listOf("calendar_find_free_slots", "calendar_create_event")),
            ),
        )
        val second = AnswerDetails(tier = Tier.BASE, firstWordMs = 700, totalMs = 1_500, atMillis = 6, guard = io.github.sandeepvijayarao09.anu.core.router.Guard.HEAT, cut = true)
        store.save(0, first)
        store.save(2, second)
        assertEquals(mapOf(0 to first, 2 to second), store.load())
        assertEquals("another conversation is apart", emptyMap<Int, AnswerDetails>(), RoomAnswerStore(db.answers(), "other").load())

        store.truncate(2)
        assertEquals(mapOf(0 to first), store.load())
        store.save(0, second)
        assertEquals("a retry replaces the answer's details", mapOf(0 to second), store.load())
    }

    @Test
    fun `real use is the middle of the last answers for one model and build, and empty without any`() = runBlocking {
        val store = RoomAnswerStore(db.answers(), "main")
        assertEquals(null, db.answers().realUse(Tier.PRO, "CPU"))
        // Three Pro answers on the CPU, one on the GPU and one from Base: only the first three count.
        store.save(0, AnswerDetails(Tier.PRO, backend = "CPU", firstWordMs = 1_000, read = 100, readMs = 1_000, written = 60, writeMs = 10_000, counted = true, atMillis = 1))
        store.save(2, AnswerDetails(Tier.PRO, backend = "CPU", firstWordMs = 9_000, read = 900, readMs = 9_000, written = 70, writeMs = 10_000, counted = true, atMillis = 2))
        store.save(4, AnswerDetails(Tier.PRO, backend = "CPU", firstWordMs = 2_000, read = 400, readMs = 2_000, written = 50, writeMs = 10_000, counted = false, atMillis = 3))
        store.save(6, AnswerDetails(Tier.PRO, backend = "GPU", firstWordMs = 300, read = 300, readMs = 100, written = 200, writeMs = 10_000, atMillis = 4))
        store.save(8, AnswerDetails(Tier.BASE, backend = "CPU", firstWordMs = 500, atMillis = 5))
        val cpu = db.answers().realUse(Tier.PRO, "CPU")!!
        assertEquals(3, cpu.answers)
        assertEquals(2_000L, cpu.firstWordMs)
        assertEquals(100.0, cpu.readPerSecond!!, 0.001)
        assertEquals(6.0, cpu.writePerSecond!!, 0.001)
        assertEquals("one estimated answer makes it an estimate", false, cpu.counted)
        assertEquals(1, db.answers().realUse(Tier.PRO, "GPU")!!.answers)
        assertEquals("the newest two only", 2, db.answers().realUse(Tier.PRO, "CPU", limit = 2)!!.answers)
    }

    @Test
    fun `photos are kept by id and messages keep their ids, and a deleted one is gone`() = runBlocking {
        val photos = RoomPhotoStore(db.photos())
        val id = photos.save(byteArrayOf(9, 8, 7))
        assertEquals(listOf<Byte>(9, 8, 7), photos.load(listOf(id)).getValue(id).toList())
        val store = RoomTranscriptStore(db.transcripts(), "main")
        val asked = ChatMessage.User("What does this say?", listOf(io.github.sandeepvijayarao09.anu.core.model.Photo(id)))
        store.append(listOf(asked, ChatMessage.Model("It says LUNCH 12:30.")))
        assertEquals(listOf(id), (store.load().first() as ChatMessage.User).photos.map { it.id })
        photos.delete(listOf(id))
        assertEquals(emptyMap<Long, ByteArray>(), photos.load(listOf(id)))
    }

    @Test
    fun `a live camera frame keeps what its detector found beside its id, and plain ids still read`() = runBlocking {
        val store = RoomTranscriptStore(db.transcripts(), "main")
        val asked = ChatMessage.User("What is this?", listOf(Photo(7, found = "cup 91%, donut 90%"), Photo(8), Photo(9, found = "")))
        store.append(listOf(asked))
        val photos = (store.load().single() as ChatMessage.User).photos
        assertEquals(listOf(7L, 8L, 9L), photos.map { it.id })
        assertEquals(listOf("cup 91%, donut 90%", null, ""), photos.map { it.found })
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

    @Test
    fun `connected services and stores are saved, replaced and removed`() = runBlocking {
        val settings = RoomConnectorSettings(db.connectors())
        settings.saveMcpServer(McpServerEntity("s1", "Notes", "https://notes.example.com/mcp", "ref-1", 1))
        settings.saveMcpServer(McpServerEntity("s1", "My Notes", "https://notes.example.com/mcp", null, 1))
        assertEquals(listOf("My Notes"), settings.mcpServers().first().map { it.name })
        settings.saveStore(StoreEntity("shoes.example.com", "shoes.example.com", "https://shoes.example.com/ucp/v1", "dev.ucp.shopping.checkout", "2026-08-25", 2))
        assertEquals(1, settings.stores().first().size)
        settings.deleteMcpServer("s1")
        settings.deleteStore("shoes.example.com")
        assertEquals(0, settings.mcpServers().first().size + settings.stores().first().size)
    }
}
