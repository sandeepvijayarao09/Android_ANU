package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
    private fun agent(brains: Map<Tier, ScriptedBrain>, broker: ToolBroker = ToolBroker(emptyList(), RecordingGate())) =
        Agent(Router(), BrainProvider { brains[it] }, broker)

    private val session = ChatSession(agent(mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)))
    private val device = DeviceState(online = true, availableTiers = Tier.entries.toSet())

    @Test
    fun `completed turns join the transcript and are sent as history next time`() = runTest {
        base.enqueue(Step.Reply("Hi!"), Step.Reply("Sure."))
        session.send("Hello", device).toList()
        session.send("Remind me later", device).toList()
        assertEquals(
            listOf(ChatMessage.User("Hello"), ChatMessage.Model("Hi!"), ChatMessage.User("Remind me later"), ChatMessage.Model("Sure.")),
            session.history(),
        )
        assertEquals(3, base.requests[1].transcript.size)
    }

    @Test
    fun `a turn blocked for consent leaves the transcript unchanged, and consent resends it`() = runTest {
        val cloudOnly = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        val blocked = session.send("Draft my resignation letter", cloudOnly).toList()
        assertTrue(blocked.single() is AgentEvent.ConsentNeeded)
        assertTrue(session.history().isEmpty())

        cloud.enqueue(Step.Reply("Here is a draft."))
        session.send("Draft my resignation letter", cloudOnly, cloudConsented = true).toList()
        assertEquals(2, session.history().size)
    }

    @Test
    fun `retry with Pro replaces the last exchange`() = runTest {
        base.enqueue(Step.Reply("meh"))
        pro.enqueue(Step.Reply("better"))
        session.send("Explain tides", device).toList()
        val text = session.takeBackLast()!!
        val events = session.send(text, device, pinned = Tier.PRO).toList()
        assertEquals(Tier.PRO, (events.last() as AgentEvent.Completed).tier)
        assertEquals(listOf(ChatMessage.User("Explain tides"), ChatMessage.Model("better")), session.history())
    }

    @Test
    fun `a new session over the same store continues the saved conversation`() = runTest {
        val store = InMemoryTranscriptStore()
        val agent = agent(mapOf(Tier.BASE to base))
        base.enqueue(Step.Reply("Noted."), Step.Reply("You said to buy milk."))
        ChatSession(agent, store).send("Remember to buy milk", device).toList()

        val reopened = ChatSession(agent, store)
        assertEquals(2, reopened.history().size)
        reopened.send("What did I say?", device).toList()
        assertEquals(3, base.requests[1].transcript.size) // the saved exchange is sent as history
    }

    @Test
    fun `scheduling requests get calendar tools and the current date, plain chat gets neither`() = runTest {
        val create = FakeCreateEvent()
        val broker = ToolBroker(listOf(create), RecordingGate())
        val now = java.time.ZonedDateTime.parse("2026-09-25T10:31:00-04:00[America/New_York]")
        val scheduling = ChatSession(
            agent(mapOf(Tier.BASE to base, Tier.PRO to pro), broker),
            toolsFor = { skill -> if (skill == io.github.sandeepvijayarao09.anu.core.router.Skill.SCHEDULING) listOf(create.name) else emptyList() },
            clock = { now },
        )
        pro.enqueue(Step.Reply("Booked."))
        scheduling.send("Book a meeting with Maya tomorrow afternoon", device).toList()
        val request = pro.requests.single()
        assertEquals(listOf(create.name), request.tools.map { it.name })
        assertTrue(request.system!!, request.system!!.contains("Friday, 25 September 2026, 10:31 (America/New_York)"))

        // A fresh conversation: "Hello there" right after scheduling would count as a follow-up and keep the tools.
        val plain = ChatSession(
            agent(mapOf(Tier.BASE to base, Tier.PRO to pro), broker),
            toolsFor = { skill -> if (skill == io.github.sandeepvijayarao09.anu.core.router.Skill.SCHEDULING) listOf(create.name) else emptyList() },
            clock = { now },
        )
        base.enqueue(Step.Reply("Hi!"))
        plain.send("Hello there", device).toList()
        assertTrue(base.requests.single().tools.isEmpty())
    }

    @Test
    fun `chat is always personal, and sensitive numbers are detected`() {
        assertEquals(setOf(DataClass.PERSONAL), ChatSession.dataClassesOf(listOf(ChatMessage.User("hi"))))
        assertEquals(
            setOf(DataClass.PERSONAL, DataClass.SENSITIVE),
            ChatSession.dataClassesOf(listOf(ChatMessage.User("my card is 4111 1111 1111 1111"))),
        )
    }
}
