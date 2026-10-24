package io.github.sandeepvijayarao09.anu.agent.adk

import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.OrchestratedTurn
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class AdkOrchestratorTest {

    @Serializable
    data class Query(val query: String = "")

    @Serializable
    data class Reply(val conversationId: String, val text: String)

    @Serializable
    data class Said(val said: String)

    /** A read-only tool that answers with [said], and counts its calls. */
    private class Lookup(override val name: String, private val said: String, override val untrustedContent: UntrustedContent? = null) : Tool<Query, Said> {
        var calls = 0
        override val description = "Look up $name"
        override val input: KSerializer<Query> = Query.serializer()
        override val output: KSerializer<Said> = Said.serializer()
        override val dataClasses = setOf(DataClass.PERSONAL)
        override fun risk(input: Query) = RiskTier.READ
        override fun describe(input: Query) = "Look up $name"
        override suspend fun invoke(input: Query, context: ToolContext): Said = Said(said).also { calls++ }
    }

    /** A reply others will see: it always asks (I2). */
    private class SendReply : Tool<Reply, Said> {
        val sent = mutableListOf<Reply>()
        override val name = "messages_reply"
        override val description = "Reply in a chat"
        override val input: KSerializer<Reply> = Reply.serializer()
        override val output: KSerializer<Said> = Said.serializer()
        override val dataClasses = setOf(DataClass.PERSONAL)
        override val untrustedContent: UntrustedContent? = null
        override fun risk(input: Reply) = RiskTier.WRITE_EXTERNAL
        override fun describe(input: Reply) = "Reply to ${input.conversationId}: ${input.text}"
        override suspend fun invoke(input: Reply, context: ToolContext): Said = Said("Sent.").also { sent += input }
    }

    private class Gate(var decision: ApprovalDecision = ApprovalDecision.Approved) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest): ApprovalDecision = decision.also { asked += request }
    }

    /**
     * A brain that plays each agent's part from its instruction: with a tool to call, it calls it once and then answers
     * from the result; the brief writer writes from the notes in its instruction. Every request is recorded.
     */
    private class Actor(private val plays: List<Pair<String, (BrainRequest) -> Pair<String, ToolCall?>>>) : Brain {
        val requests: MutableList<BrainRequest> = Collections.synchronizedList(mutableListOf())
        override val id = BrainId.PRO
        override val capabilities = BrainCapabilities(BrainLocation.ON_DEVICE, 4_096, toolCalling = true, structuredOutput = true)
        override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
            requests += request
            val system = request.system.orEmpty()
            val (_, play) = plays.firstOrNull { system.contains(it.first) } ?: error("no part for: ${system.take(60)}")
            val (text, call) = play(request)
            if (text.isNotEmpty()) emit(BrainEvent.TextDelta(text))
            call?.let { emit(BrainEvent.ToolCallRequested(it)) }
            emit(BrainEvent.Usage(contextTokens = 10))
            emit(BrainEvent.Finished(if (call == null) StopReason.COMPLETE else StopReason.TOOL_CALLS))
        }
    }

    private fun call(name: String, vararg args: Pair<String, String>) = ToolCall(name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    private fun lastIsTool(request: BrainRequest) = request.transcript.last() is ChatMessage.Tool

    private fun turn(skill: Skill, text: String) = OrchestratedTurn(skill, text, "turn-1", sentAt = null, inView = emptySet())

    @Test
    fun `the coordinator hands each area to its specialist, whose tools run through the broker`() = runBlocking {
        val calendar = Lookup("calendar_list_events", "Friday: dentist at 3pm")
        val broker = ToolBroker(listOf(calendar), Gate())
        val brain = Actor(
            listOf(
                "You are Anu" to { r -> if (lastIsTool(r)) "You're free on Friday except the dentist at 3pm." to null else "" to call("calendar_agent", "request" to "What's on Friday?") },
                "You handle the user's calendar" to { r -> if (lastIsTool(r)) "Friday: dentist at 3pm." to null else "" to call("calendar_list_events", "query" to "Friday") },
            ),
        )
        val adk = AdkOrchestrator({ broker }, { setOf("calendar_list_events") }, { brain }, { Tier.PRO })
        val events = adk.run(turn(Skill.AGENTS, "What's on Friday?")).toList()

        val done = events.last() as AgentEvent.Completed
        assertEquals("You're free on Friday except the dentist at 3pm.", done.text)
        assertEquals(listOf(ChatMessage.Model(done.text)), done.added)
        assertEquals(1, calendar.calls)
        assertTrue(events.any { it is AgentEvent.ToolStarted && it.call.name == "calendar_list_events" })
        assertEquals(listOf(ActionRecord("calendar_list_events", "Look up calendar_list_events", ActionRecord.Status.DONE, wrote = false)), done.actions)
        // The specialist saw only its own tools, and the coordinator only its specialists.
        val specialist = brain.requests.first { it.system.orEmpty().contains("You handle the user's calendar") }
        assertTrue("the specialist knows the day", specialist.system.orEmpty().contains("Today is "))
        assertEquals(listOf("calendar_list_events"), specialist.tools.map { it.name })
        val coordinator = brain.requests.first { it.system.orEmpty().contains("You are Anu") }
        assertEquals(listOf("calendar_agent"), coordinator.tools.map { it.name })
    }

    @Test
    fun `a specialist's reply still needs approval of its exact payload, and a no is a receipt, not a send`() = runBlocking {
        val reply = SendReply()
        val gate = Gate(ApprovalDecision.Denied())
        val broker = ToolBroker(listOf(reply), gate)
        val brain = Actor(
            listOf(
                "You are Anu" to { r -> if (lastIsTool(r)) "I didn't send it, since you said no." to null else "" to call("messages_agent", "request" to "Tell Maya I'm late") },
                "You handle the user's messages" to { r -> if (lastIsTool(r)) "Not sent." to null else "" to call("messages_reply", "conversationId" to "maya", "text" to "I'm running late") },
            ),
        )
        val adk = AdkOrchestrator({ broker }, { setOf("messages_reply") }, { brain }, { Tier.PRO })
        val done = adk.run(turn(Skill.AGENTS, "Tell Maya I'm late")).toList().last() as AgentEvent.Completed

        assertEquals("Reply to maya: I'm running late", gate.asked.single().summary)
        assertTrue(reply.sent.isEmpty())
        assertEquals(ActionRecord.Status.NOT_DONE, done.actions.single().status)
    }

    @Test
    fun `the daily brief gathers in parallel, then a writer turns the notes into the brief`() = runBlocking {
        val calendar = Lookup("calendar_list_events", "Today: standup at 10am")
        val messages = Lookup("messages_list_conversations", "Maya asked about Friday", UntrustedContent.MESSAGE)
        val notes = Lookup("notes_search", "Groceries: oat milk")
        val broker = ToolBroker(listOf(calendar, messages, notes), Gate())
        val brain = Actor(listOf("Write the user's daily brief" to { r -> "Brief from: ${r.system}" to null }))
        val adk = AdkOrchestrator(
            { broker }, { setOf("calendar_list_events", "messages_list_conversations", "notes_search") }, { brain }, { Tier.PRO },
            today = { java.time.LocalDate.of(2026, 10, 24) },
        )
        val ran = adk.run(turn(Skill.BRIEF, "Brief me")).toList()
        val done = ran.last() as? AgentEvent.Completed ?: error("brief failed: ${ran.last()}; writer saw ${brain.requests.map { it.system?.take(40) }}")

        assertEquals(1, calendar.calls)
        assertEquals(1, messages.calls)
        assertEquals(1, notes.calls)
        // One model call, the writer's, whose instruction carries each step's result from the session's state.
        assertEquals(1, brain.requests.size)
        assertEquals("the writer can't transfer back into the workflow", emptyList<String>(), brain.requests.single().tools.map { it.name })
        assertTrue(done.text, done.text.contains("standup at 10am") && done.text.contains("Maya asked about Friday") && done.text.contains("oat milk"))
        assertEquals(3, done.actions.size)
    }

    @Test
    fun `no on-device model means a failed turn, not a cloud call`() = runBlocking {
        val adk = AdkOrchestrator({ ToolBroker(emptyList(), Gate()) }, { emptySet() }, { null }, { Tier.PRO })
        val events = adk.run(turn(Skill.AGENTS, "Check my calendar and text Maya")).toList()
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test
    fun `ADK's contents become Anu's transcript, and schemas and values convert both ways`() {
        val contents = listOf(
            com.google.adk.kt.types.Content.fromText("user", "What's on Friday?"),
            com.google.adk.kt.types.Content.fromText("user", "For context: the coordinator asked."),
            com.google.adk.kt.types.Content(role = "model", parts = listOf(com.google.adk.kt.types.Part(functionCall = com.google.adk.kt.types.FunctionCall("calendar_list_events", mapOf("days" to 2.0), "c1")))),
            com.google.adk.kt.types.Content(role = "user", parts = listOf(com.google.adk.kt.types.Part(functionResponse = com.google.adk.kt.types.FunctionResponse("calendar_list_events", mapOf("said" to "dentist"), "c1")))),
        )
        val transcript = AdkBridge.transcript(contents)
        assertEquals(ChatMessage.User("What's on Friday?\n\nFor context: the coordinator asked."), transcript[0])
        assertEquals(ToolCall("calendar_list_events", buildJsonObject { put("days", 2) }), (transcript[1] as ChatMessage.Model).toolCalls.single())
        assertEquals(buildJsonObject { put("said", "dentist") }, (transcript[2] as ChatMessage.Tool).results.single().result)
        assertEquals(ChatMessage.User(AdkBridge.GO_ON), AdkBridge.transcript(emptyList()).single())

        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string"); put("description", "What to find") }) })
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
        }
        assertEquals(schema, AdkBridge.json(AdkBridge.schema(schema)))
        assertEquals(JsonObject(mapOf("n" to JsonPrimitive(3), "ok" to JsonPrimitive(true))), AdkBridge.jsonObject(mapOf("n" to 3.0, "ok" to true)))
    }
}
