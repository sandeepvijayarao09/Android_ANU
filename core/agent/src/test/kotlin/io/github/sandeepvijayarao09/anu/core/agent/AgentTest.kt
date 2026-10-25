package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.NOTE_HEADER
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.model.userText
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val remember = FakeRemember()
    private val notes = FakeRemoteLookup()
    private val rememberRoute = RouteRequest("Remember that I buy oat milk every week", Skill.MEMORY, setOf(DataClass.PERSONAL), promptTokens = 100)
    private val searchNotes = ToolCall("mcp_notes_search", buildJsonObject { put("query", "oat milk") })
    private val oatMilk = ToolCall(ActionClaims.REMEMBER_TOOL, buildJsonObject { put("what", "buys_weekly"); put("value", "oat milk") })

    private suspend fun remembering(gate: ApprovalGate, tiers: Set<Tier> = setOf(Tier.PRO)) =
        Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro)[it] }, ToolBroker(listOf(remember, notes), gate))
            .run(
                rememberRoute, DeviceState(online = true, availableTiers = tiers), "You are Anu.",
                listOf(ChatMessage.User(rememberRoute.text)), listOf(remember.name, notes.name), "t1",
            ).toList()

    @Test
    fun `a declined memory_remember is never answered with "I've noted"`() = runTest {
        // Reading the notes service taints the run, so remembering asks the user (§7.1), who says no.
        pro.enqueue(
            Step.Reply(listOf(""), toolCalls = listOf(searchNotes)),
            Step.Reply(listOf(""), toolCalls = listOf(oatMilk)),
            Step.Reply("I've noted that you typically buy oat milk every week. Is there anything else you'd like me to do with your notes or plans?"),
            Step.Reply("Okay, I won't remember that."),
        )
        val gate = RecordingGate(ApprovalDecision.Denied())
        val events = remembering(gate)
        assertEquals(ActionClaims.REMEMBER_TOOL, gate.requests.single().toolName)
        assertTrue(remember.remembered.isEmpty())
        assertTrue(events.contains(AgentEvent.Discarded(Tier.PRO, ActionClaims.Claim.MEMORY.why)))
        assertEquals("${ActionClaims.NOTHING_REMEMBERED_NOTE} ${ActionClaims.REMEMBER_HINT}", pro.requests[3].note)
        assertEquals("the note goes with the message, not the system prompt", pro.requests[2].system, pro.requests[3].system)
        assertEquals("the repair is greedy", Sampling.Precise, pro.requests[3].sampling)
        val done = events.last() as AgentEvent.Completed
        assertEquals("Okay, I won't remember that.", done.text)
        assertFalse(done.unverifiedClaim)
        assertEquals(ActionRecord.Status.NOT_DONE, done.actions.single { it.tool == ActionClaims.REMEMBER_TOOL }.status)
    }

    @Test
    fun `a memory claim that survives repair and escalation is shown with the memory warning`() = runTest {
        base.enqueue(
            Step.Reply(listOf(""), toolCalls = listOf(searchNotes)),
            Step.Reply(listOf(""), toolCalls = listOf(oatMilk)),
            Step.Reply("I've noted that you buy oat milk every week."),
            Step.Reply("Noted!"),
        )
        pro.enqueue(Step.Reply("Got it, I'll keep that in mind."))
        val events = remembering(RecordingGate(ApprovalDecision.Denied()), tiers = setOf(Tier.BASE, Tier.PRO))
        assertTrue(events.contains(AgentEvent.Escalated(Tier.BASE, Tier.PRO, ActionClaims.Claim.MEMORY.why)))
        assertEquals("the stronger tier is told too", ActionClaims.NOTHING_REMEMBERED_NOTE + " " + ActionClaims.REMEMBER_HINT, pro.requests.single().note)
        val done = events.last() as AgentEvent.Completed
        assertTrue(done.unverifiedClaim)
        assertEquals("Got it, I'll keep that in mind.\n\n${ActionClaims.NOT_REMEMBERED_WARNING}", done.text)
        assertEquals(done.text, (done.added.last() as ChatMessage.Model).text) // the transcript keeps the truth too
        assertTrue(remember.remembered.isEmpty())
    }

    @Test
    fun `a memory_remember that ran backs "I've noted"`() = runTest {
        pro.enqueue(Step.Reply(listOf(""), toolCalls = listOf(oatMilk)), Step.Reply("I've noted that you buy oat milk every week."))
        val events = remembering(RecordingGate())
        assertTrue(events.none { it is AgentEvent.Discarded })
        assertEquals("I've noted that you buy oat milk every week.", (events.last() as AgentEvent.Completed).text)
        assertEquals(listOf(RememberInput("buys_weekly", "oat milk")), remember.remembered)
    }

    @Test
    fun `"Noted!" with nothing saved is repaired, and a save made then backs the answer`() = runTest {
        pro.enqueue(
            Step.Reply("Noted! I'll remember that."),
            Step.Reply(listOf(""), toolCalls = listOf(oatMilk)),
            Step.Reply("I've noted that you buy oat milk every week."),
        )
        val events = remembering(RecordingGate())
        assertEquals(1, events.count { it is AgentEvent.Discarded })
        val done = events.last() as AgentEvent.Completed
        assertEquals("I've noted that you buy oat milk every week.", done.text)
        assertFalse(done.unverifiedClaim)
        assertEquals(1, remember.remembered.size)
    }

    @Test
    fun `a plain chat that says "noted" is repaired too, without being told to call a tool it doesn't have`() = runTest {
        // As on the emulator: no memory tool is offered, and E4B answered "I've noted that you are vegetarian".
        val chat = RouteRequest("By the way, I'm vegetarian, so please keep that in mind", Skill.CHAT, setOf(DataClass.PERSONAL), promptTokens = 100)
        pro.enqueue(Step.Reply("I've noted that you are vegetarian."), Step.Reply("Thanks for telling me! How about a mushroom risotto tonight?"))
        val events = agent.run(chat, DeviceState(online = true, availableTiers = setOf(Tier.PRO)), "You are Anu.", listOf(ChatMessage.User(chat.text)), emptyList(), "t1").toList()
        assertEquals("no memory tool to call", ActionClaims.NOTHING_REMEMBERED_NOTE, pro.requests[1].note)
        assertTrue(pro.requests[1].userText(0).endsWith("$NOTE_HEADER ${ActionClaims.NOTHING_REMEMBERED_NOTE}"))
        assertEquals("Thanks for telling me! How about a mushroom risotto tonight?", (events.last() as AgentEvent.Completed).text)
    }

    @Test
    fun `escalating into the cloud with personal data stops for consent`() = runTest {
        base.enqueue(Step.Fail(IllegalStateException("boom")))
        pro.enqueue(Step.Fail(IllegalStateException("boom")))
        val events = run(DeviceState(online = true, availableTiers = Tier.entries.toSet()))
        assertTrue(events.last() is AgentEvent.ConsentNeeded)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun `a turn that can act samples cooler, and a plain chat keeps Gemma's defaults`() = runTest {
        base.enqueue(Step.Reply(listOf(""), toolCalls = listOf(createDentist)), Step.Reply("Added."))
        run()
        assertEquals(listOf(Sampling.Acting, Sampling.Acting), base.requests.map { it.sampling })

        base.enqueue(Step.Reply("Hello!"))
        Agent(Router(), BrainProvider { mapOf(Tier.BASE to base)[it] }, broker)
            .run(route, device, "You are Anu.", listOf(ChatMessage.User("Hi")), emptyList(), "t2").toList()
        assertEquals(Sampling.Default, base.requests.last().sampling)
    }

    private val health = FakeHealthRecords()
    private val lookup = ToolCall(health.name, buildJsonObject { put("query", "allergies") })
    private val everyTier = DeviceState(online = true, availableTiers = Tier.entries.toSet())
    private fun List<ChatMessage>.results() = filterIsInstance<ChatMessage.Tool>().single().results.single().result

    @Test
    fun `a sensitive result from the phone never goes to the cloud, even when the turn escalates with consent`() = runTest {
        val agent = Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)[it] }, ToolBroker(listOf(health), gate))
        base.enqueue(Step.Reply(listOf(""), toolCalls = listOf(lookup)), Step.Fail(IllegalStateException("out of memory")))
        pro.enqueue(Step.Fail(IllegalStateException("out of memory")))
        cloud.enqueue(Step.Reply("That stays on your phone."))

        val events = agent.run(route.copy(cloudConsented = true), everyTier, "You are Anu.", history, listOf(health.name), "t1").toList()

        val completed = events.last() as AgentEvent.Completed
        assertEquals(Tier.CLOUD, completed.tier)
        assertTrue("the phone's brains read it", "penicillin" in pro.requests.single().transcript.results().toString())
        assertEquals(KEPT_ON_PHONE, cloud.requests.single().transcript.results())
        assertEquals("the ledger names only what goes", setOf(DataClass.PERSONAL), cloud.requests.single().dataClasses)
        assertTrue("the saved chat keeps it", "penicillin" in completed.added.results().toString())
    }

    @Test
    fun `an earlier turn's sensitive result reaches the cloud only as a note that it stays on the phone`() = runTest {
        val agent = Agent(Router(), BrainProvider { mapOf(Tier.PRO to pro, Tier.CLOUD to cloud)[it] }, ToolBroker(listOf(health), gate))
        val earlier = listOf(
            ChatMessage.User("Do I have any allergies?"),
            ChatMessage.Model("", listOf(lookup)),
            ChatMessage.Tool(listOf(ToolResult(health.name, buildJsonObject { putJsonArray("items") { add(JsonPrimitive("allergy: penicillin")) } }))),
            ChatMessage.Model("I found one."),
            ChatMessage.User("Plan a weekend in Kyoto"),
        )
        pro.enqueue(Step.Reply("Here's a plan."))
        cloud.enqueue(Step.Reply("Here's a plan."))

        // This turn offers no tools, and the result is still kept back: it goes by the tool that returned it.
        for (tier in listOf(Tier.CLOUD, Tier.PRO)) {
            agent.run(route.copy(text = "Plan a weekend in Kyoto", pinnedTier = tier, cloudConsented = true), everyTier, "You are Anu.", earlier, emptyList(), "t2").toList()
        }

        val sent = cloud.requests.single().transcript
        assertFalse(sent.toString(), "penicillin" in sent.toString())
        assertEquals(KEPT_ON_PHONE, sent.results())
        assertTrue("the phone's brains still read it", "penicillin" in pro.requests.single().transcript.results().toString())
    }
}
