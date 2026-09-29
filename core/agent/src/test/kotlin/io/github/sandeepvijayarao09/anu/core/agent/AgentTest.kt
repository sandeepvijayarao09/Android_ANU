package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
    private val create = FakeCreateEvent()
    private val inbox = FakeReadInbox()
    private val gate = RecordingGate()
    private val broker = ToolBroker(listOf(create, inbox), gate)
    private val agent = Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)[it] }, broker)
    private val device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO))

    private val route = RouteRequest("Add a dentist appointment", Skill.CHAT, setOf(DataClass.PERSONAL), promptTokens = 100)
    private val history = listOf(ChatMessage.User("Add a dentist appointment"))
    private val createDentist = ToolCall("calendar_create_event", buildJsonObject { put("title", "Dentist") })

    private suspend fun run(device: DeviceState = this.device, route: RouteRequest = this.route) =
        agent.run(route, device, system = "You are Anu.", history = history, tools = listOf(create.name, inbox.name), turnKey = "t1").toList()

    @Test
    fun `tool calls go through the broker and their results come back to the model`() = runTest {
        base.enqueue(Step.Reply(listOf(""), toolCalls = listOf(createDentist)), Step.Reply("Added."))
        val events = run()
        val completed = events.last() as AgentEvent.Completed
        assertEquals("Added.", completed.text)
        assertEquals(3, completed.added.size) // call, result, answer
        assertEquals(listOf(EventInput("Dentist")), create.created)
        val second = base.requests[1]
        assertTrue(second.transcript.last() is ChatMessage.Tool)
        assertEquals(listOf(create.name, inbox.name), second.tools.map { it.name })
    }

    @Test
    fun `a completed turn reports its estimate against the runtime's count`() = runTest {
        base.enqueue(Step.Reply(listOf(""), toolCalls = listOf(createDentist)), Step.Reply("Added."))
        val completed = run().last() as AgentEvent.Completed
        val final = base.requests[1]
        assertEquals(
            TokenSample(Tokens.ofRequest(final) + Tokens.of(ChatMessage.Model("Added.")), counted = "Added.".length, onDevice = true),
            completed.tokens,
        )
    }

    @Test
    fun `escalation continues the same transcript, so side effects never repeat`() = runTest {
        base.enqueue(Step.Reply(listOf(""), toolCalls = listOf(createDentist)), Step.Fail(IllegalStateException("out of memory")))
        pro.enqueue(Step.Reply("Done, it's on your calendar."))
        val events = run()
        assertTrue(events.contains(AgentEvent.Escalated(Tier.BASE, Tier.PRO, "error: out of memory")))
        assertEquals(Tier.PRO, (events.last() as AgentEvent.Completed).tier)
        assertEquals(1, create.created.size)
        assertTrue(pro.requests.single().transcript.any { it is ChatMessage.Tool })
    }

    @Test
    fun `repeated invalid calls escalate to a stronger tier`() = runTest {
        val bad = ToolCall("calendar_create_event", JsonObject(emptyMap()))
        base.enqueue(Step.Reply(listOf(""), toolCalls = listOf(bad)), Step.Reply(listOf(""), toolCalls = listOf(bad)))
        pro.enqueue(Step.Reply(listOf(""), toolCalls = listOf(createDentist)), Step.Reply("Added."))
        val events = run()
        assertTrue(events.contains(AgentEvent.Escalated(Tier.BASE, Tier.PRO, "repeated invalid tool calls")))
        assertEquals(listOf(EventInput("Dentist")), create.created)
    }

    @Test
    fun `an empty answer is repaired once with greedy sampling`() = runTest {
        base.enqueue(Step.Reply(""), Step.Reply("Here you go."))
        val events = run()
        assertEquals(Sampling.Precise, base.requests[1].sampling)
        assertEquals(Tier.BASE, (events.last() as AgentEvent.Completed).tier)
    }

    @Test
    fun `a prompt injection in tool results cannot send anything without approval`() = runTest {
        val injected = ToolCall("calendar_create_event", buildJsonObject { put("title", "Everyone") })
        base.enqueue(
            Step.Reply(listOf(""), toolCalls = listOf(ToolCall("email_search", buildJsonObject { put("query", "x") }))),
            Step.Reply(listOf(""), toolCalls = listOf(injected)),
            Step.Reply("Done."),
        )
        gate.decision = ApprovalDecision.Denied("I never asked for that")
        run()
        assertEquals("Anu asks because this chat shows an email, which others can write. Check this closely", gate.requests.single().reason)
        assertTrue(create.created.isEmpty())
    }

    @Test
    fun `tool results record the kind of content from others they hold, and a call that never ran holds none`() = runTest {
        base.enqueue(
            Step.Reply(listOf(""), toolCalls = listOf(ToolCall("email_search", buildJsonObject { put("query", "x") }), ToolCall("made_up_tool", buildJsonObject { }))),
            Step.Reply("Nothing new."),
        )
        val results = (run().last() as AgentEvent.Completed).added.filterIsInstance<ChatMessage.Tool>().single().results
        assertEquals(listOf("email_search" to UntrustedContent.EMAIL, "made_up_tool" to null), results.map { it.toolName to it.untrusted })
        assertTrue(results.all { it.untrustedKnown })
    }

    @Test
    fun `the step budget stops a looping model`() = runTest {
        val looping = Agent(Router(), BrainProvider { base }, broker, Budgets(maxSteps = 2))
        base.enqueue(
            Step.Reply(listOf(""), toolCalls = listOf(createDentist)),
            Step.Reply(listOf(""), toolCalls = listOf(createDentist)),
        )
        val events = looping.run(route, device, "You are Anu.", history, listOf(create.name), "t1").toList()
        assertEquals(AgentEvent.Failed(listOf("stopped after 2 steps")), events.last())
    }

    @Test
    fun `escalating into the cloud with personal data stops for consent`() = runTest {
        base.enqueue(Step.Fail(IllegalStateException("boom")))
        pro.enqueue(Step.Fail(IllegalStateException("boom")))
        val events = run(DeviceState(online = true, availableTiers = Tier.entries.toSet()))
        assertTrue(events.last() is AgentEvent.ConsentNeeded)
        assertTrue(cloud.requests.isEmpty())
    }
}
