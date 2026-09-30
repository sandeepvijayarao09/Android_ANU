package io.github.sandeepvijayarao09.anu.core.memory

import io.github.sandeepvijayarao09.anu.core.agent.ActionClaims
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.PayloadRow
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.RunState
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.agent.ToolOutcome
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsTest {

    private val store = InMemoryMemoryStore()
    private val memory = MemoryPipeline(store, FactExtractor({ null }))
    private val ctx = ToolContext("main:4", "key")
    private fun run() = RunState("main:4", "main:4", BrainLocation.ON_DEVICE, false)

    @Serializable
    data class NoteQuery(val query: String)

    @Serializable
    data class NoteText(val text: String)

    /** A note someone else can write, like the mock Notes server's: reading it taints the run (P4). */
    private class SharedNotes : Tool<NoteQuery, NoteText> {
        override val name = "mcp_notes_search_notes"
        override val description = "Search the user's notes"
        override val input = NoteQuery.serializer()
        override val output = NoteText.serializer()
        override val dataClasses = setOf(DataClass.PERSONAL)
        override val untrustedContent = UntrustedContent.SERVICE
        override fun risk(input: NoteQuery) = RiskTier.READ
        override fun describe(input: NoteQuery) = "Search your notes for \"${input.query}\""
        override suspend fun invoke(input: NoteQuery, context: ToolContext) = NoteText("Groceries: oat milk, eggs, spinach")
    }

    @Test
    fun `remember keeps the user's statement at once, and refuses secrets before running`() = runTest {
        val tool = RememberTool(memory)
        assertEquals("the claim check knows it by name (ADR-089)", ActionClaims.REMEMBER_TOOL, tool.name)
        val input = RememberInput(what = "manager", value = "Maya Chen", section = ProfileSection.PEOPLE)
        assertEquals(RiskTier.WRITE_PRIVATE, tool.risk(input))
        assertEquals("Remember that your manager is Maya Chen", tool.describe(input))
        assertEquals("manager: Maya Chen", tool.invoke(input, ctx).remembered)

        val fact = store.activeFacts().single()
        assertEquals(FactOrigin.USER_STATED, fact.origin)
        val event = store.allEvents().single()
        assertEquals(EventSource.STATEMENT, event.source)
        assertEquals("main:4", event.ref)
        assertEquals(listOf(event.id), fact.sources)

        assertThrows(IllegalArgumentException::class.java) {
            tool.describe(RememberInput(what = "bank_card", value = "4111 1111 1111 1111", section = ProfileSection.IDENTITY))
        }
        assertThrows(IllegalArgumentException::class.java) {
            tool.describe(RememberInput(what = "wifi_password", value = "hunter22", section = ProfileSection.IDENTITY))
        }
    }

    @Test
    fun `remembering about someone else names them`() = runTest {
        val tool = RememberTool(memory)
        val input = RememberInput(about = "Raj", what = "birthday", value = "12 March", section = ProfileSection.PEOPLE)
        assertEquals("Remember that Raj's birthday is 12 March", tool.describe(input))
        tool.invoke(input, ctx)
        assertEquals("Raj", store.activeFacts().single().subject)
    }

    @Test
    fun `after reading someone else's note, remembering asks, in words rather than fields, for the exact payload`() = runTest {
        memory.start()
        val asked = mutableListOf<ApprovalRequest>()
        val broker = ToolBroker(listOf(RememberTool(memory), SharedNotes()), ApprovalGate { asked += it; ApprovalDecision.Approved })
        val state = run()
        broker.execute(ToolCall("mcp_notes_search_notes", buildJsonObject { put("query", "groceries") }), state)
        // What E4B sent on the emulator for "Remember that I buy oat milk every week".
        val arguments = buildJsonObject {
            put("about", "user")
            put("what", "buys_at")
            put("value", "buy oat milk every week")
            put("section", "routines")
        }
        assertTrue(broker.execute(ToolCall("memory_remember", arguments), state) is ToolOutcome.Ok)

        val request = asked.single()
        assertEquals("Remember that you buy oat milk every week", request.summary)
        assertEquals(listOf(PayloadRow("Fact", "You buy oat milk every week"), PayloadRow("Kept in", "Routines")), request.rows)
        assertEquals("the approval is still of the exact payload that runs (I2)", buildJsonObject {
            put("about", "user")
            put("what", "buys_at")
            put("value", "buy oat milk every week")
            put("section", "routines")
            put("sensitive", false)
        }, request.payload)
        assertEquals("buys at: buy oat milk every week", store.activeFacts().single().text)
    }

    @Test
    fun `the rows show the fact as it will be kept, and sensitive only when it is`() {
        val tool = RememberTool(memory)
        assertEquals(
            "the subject and relation are normalized as the pipeline stores them",
            listOf(PayloadRow("Fact", "Your home city is Boston"), PayloadRow("Kept in", "Places")),
            tool.rows(RememberInput(about = "me", what = "Home City", value = " Boston ", section = ProfileSection.PLACES)),
        )
        assertEquals(
            listOf(PayloadRow("Fact", "Your allergy is penicillin"), PayloadRow("Kept in", "About you"), PayloadRow("Sensitive", "Yes")),
            tool.rows(RememberInput(what = "allergy", value = "penicillin", section = ProfileSection.IDENTITY, sensitive = true)),
        )
    }

    @Test
    fun `search finds facts by name or topic, and "me" means the user`() = runTest {
        memory.remember("user", "manager", "Maya Chen", ProfileSection.PEOPLE, false, null)
        memory.remember("Raj", "birthday", "12 March", ProfileSection.PEOPLE, false, null)
        memory.remember("user", "diet", "vegetarian", ProfileSection.PREFERENCES, false, null)
        val tool = SearchMemoryTool(memory)
        assertEquals(RiskTier.READ, tool.risk(SearchMemoryInput()))
        assertEquals(listOf("manager: Maya Chen"), tool.invoke(SearchMemoryInput("What do you know about Maya?"), ctx).facts.map { it.fact })
        assertEquals(
            setOf("manager: Maya Chen", "diet: vegetarian"),
            tool.invoke(SearchMemoryInput("about me"), ctx).facts.map { it.fact }.toSet(),
        )
        assertEquals(3, tool.invoke(SearchMemoryInput(), ctx).facts.size)
        assertEquals("the user said so", tool.invoke(SearchMemoryInput("Raj"), ctx).facts.single().source)
    }

    @Test
    fun `a sensitive fact never reaches the cloud through memory_search, even after consent`() = runTest {
        memory.start()
        memory.remember("user", "allergy", "penicillin", ProfileSection.IDENTITY, sensitive = true, ref = null)
        memory.remember("user", "diet", "vegetarian", ProfileSection.PREFERENCES, sensitive = false, ref = null)
        val broker = ToolBroker(listOf(SearchMemoryTool(memory)), ApprovalGate { ApprovalDecision.Approved })
        // The user said yes to Gemini for the message and the facts on the consent sheet, never to what a tool returns.
        val cloud = RunState("main:4", "main:4", BrainLocation.CLOUD, cloudConsented = true)

        for (query in listOf("", "allergy")) {
            val outcome = broker.execute(ToolCall("memory_search", buildJsonObject { put("query", query) }), cloud)
            // What the broker returns is what Gemini reads in its next request.
            assertFalse("\"$query\": $outcome", "penicillin" in outcome.toResultJson().toString())
            assertTrue(outcome.toString(), outcome is ToolOutcome.Rejected)
        }
        // On the phone, the same search still finds it.
        val onPhone = broker.execute(ToolCall("memory_search", buildJsonObject { put("query", "allergy") }), run())
        assertTrue(onPhone.toString(), "penicillin" in onPhone.toResultJson().toString())
    }

    @Test
    fun `forgetting from the cloud is refused before anyone is asked, since its result names the facts`() = runTest {
        memory.start()
        memory.remember("user", "allergy", "penicillin", ProfileSection.IDENTITY, sensitive = true, ref = null)
        val asked = mutableListOf<String>()
        val broker = ToolBroker(listOf(ForgetMemoryTool(memory)), ApprovalGate { asked += it.summary; ApprovalDecision.Approved })
        val cloud = RunState("main:4", "main:4", BrainLocation.CLOUD, cloudConsented = true)

        val outcome = broker.execute(ToolCall("memory_forget", buildJsonObject { put("query", "allergy") }), cloud)

        assertTrue(outcome.toString(), outcome is ToolOutcome.Rejected)
        assertFalse("penicillin" in outcome.toResultJson().toString())
        assertTrue("nothing to approve", asked.isEmpty())
        assertEquals(1, store.activeFacts().size)
    }

    @Test
    fun `forgetting always asks, shows the exact facts, and deletes only what was approved`() = runTest {
        memory.start()
        memory.remember("user", "diet", "vegetarian", ProfileSection.PREFERENCES, false, null)
        val asked = mutableListOf<String>()
        val broker = ToolBroker(listOf(ForgetMemoryTool(memory)), ApprovalGate { request ->
            asked += request.summary
            // Something new that matches arrives while the user looks at the sheet.
            memory.remember("user", "diet_note", "vegetarian on weekdays", ProfileSection.PREFERENCES, false, null)
            ApprovalDecision.Approved
        })

        val outcome = broker.execute(ToolCall("memory_forget", buildJsonObject { put("query", "vegetarian") }), run())

        assertTrue(outcome.toString(), outcome is ToolOutcome.Ok)
        assertEquals(listOf("Forget that your diet is vegetarian"), asked)
        assertEquals("only the approved fact is gone", listOf("diet note: vegetarian on weekdays"), store.activeFacts().map { it.text })
    }

    @Test
    fun `forgetting lists the facts it will delete in words, not ids and a search`() = runTest {
        memory.start()
        memory.remember("user", "buys_at", "buy oat milk every week", ProfileSection.ROUTINES, false, null)
        val asked = mutableListOf<ApprovalRequest>()
        val broker = ToolBroker(listOf(ForgetMemoryTool(memory)), ApprovalGate { asked += it; ApprovalDecision.Denied("no") })

        broker.execute(ToolCall("memory_forget", buildJsonObject { put("query", "oat milk") }), run())

        val request = asked.single()
        assertEquals("Forget that you buy oat milk every week", request.summary)
        assertEquals(listOf(PayloadRow("Fact", "You buy oat milk every week")), request.rows)
        assertEquals(buildJsonObject {
            putJsonArray("ids") {}
            put("query", "oat milk")
        }, request.payload)
        assertEquals("declined, so nothing is deleted", 1, store.activeFacts().size)
    }

    @Test
    fun `forgetting what isn't there is refused before anyone is asked`() = runTest {
        memory.start()
        val asked = mutableListOf<String>()
        val broker = ToolBroker(listOf(ForgetMemoryTool(memory)), ApprovalGate { asked += it.summary; ApprovalDecision.Approved })
        val outcome = broker.execute(
            ToolCall("memory_forget", buildJsonObject { putJsonArray("ids") { add(kotlinx.serialization.json.JsonPrimitive(42)) } }),
            run(),
        )
        assertTrue(outcome.toString(), outcome is ToolOutcome.Invalid && "memory_search" in outcome.reason)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a declined forget deletes nothing`() = runTest {
        memory.start()
        memory.remember("user", "diet", "vegetarian", ProfileSection.PREFERENCES, false, null)
        val id = store.activeFacts().single().id
        val broker = ToolBroker(listOf(ForgetMemoryTool(memory)), ApprovalGate { ApprovalDecision.Denied("no") })
        val outcome = broker.execute(ToolCall("memory_forget", buildJsonObject { putJsonArray("ids") { add(kotlinx.serialization.json.JsonPrimitive(id)) } }), run())
        assertTrue(outcome is ToolOutcome.Rejected)
        assertEquals(1, store.activeFacts().size)
    }
}
