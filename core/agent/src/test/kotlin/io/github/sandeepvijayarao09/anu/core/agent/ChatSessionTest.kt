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
        assertTrue(request.system!!, request.system!!.contains("Friday, 25 September 2026 (America/New_York)"))
        assertEquals("Fri 2026-09-25 10:31", request.sentAt)
        assertTrue(request.system!!.contains("call calendar_create_event"))
        assertTrue("hints for tools it wasn't given", "messages_reply" !in request.system!!)

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
    fun `the system prompt stays the same from turn to turn, and the time travels with the message`() = runTest {
        var now = java.time.ZonedDateTime.parse("2026-09-25T10:31:00-04:00[America/New_York]")
        val chat = ChatSession(agent(mapOf(Tier.BASE to base)), clock = { now })
        base.enqueue(Step.Reply("Hi!"), Step.Reply("Sure."))
        chat.send("Hello", device).toList()
        now = now.plusMinutes(7)
        chat.send("And now?", device).toList()
        // A prompt that changed every minute would make the on-device model re-read the whole chat (ADR-018).
        assertEquals(base.requests[0].system, base.requests[1].system)
        assertEquals(listOf("Fri 2026-09-25 10:31", "Fri 2026-09-25 10:38"), base.requests.map { it.sentAt })
    }

    @Test
    fun `every turn carries the current memory, and is offered to memory when it completes`() = runTest {
        var block = "- diet: vegetarian"
        val offered = mutableListOf<Pair<String, String>>()
        val chat = ChatSession(
            agent(mapOf(Tier.BASE to base)),
            memory = { block },
            onTurnCompleted = { text, key -> offered += text to key },
        )
        base.enqueue(Step.Reply("Hi!"), Step.Reply("Sure."), Step.Reply("Noted."))
        chat.send("Hello", device).toList()
        chat.send("And now?", device).toList()
        block = "- diet: vegan" // corrected in the Memory tab
        chat.send("Dinner ideas?", device).toList()
        assertEquals(listOf("- diet: vegetarian", "- diet: vegetarian", "- diet: vegan"), base.requests.map { it.memory })
        // An unchanged block keeps the system prompt identical, so the on-device cache survives (ADR-038).
        assertEquals(base.requests[0].system, base.requests[1].system)
        assertEquals(listOf("Hello" to "main:0", "And now?" to "main:2", "Dinner ideas?" to "main:4"), offered)
    }

    @Test
    fun `a long chat shows the model its newest exchanges, keeps the start steady, and says so`() = runTest {
        val now = java.time.ZonedDateTime.parse("2026-09-25T10:31:00-04:00[America/New_York]")
        val chat = ChatSession(agent(mapOf(Tier.BASE to base)), clock = { now }, window = ContextWindow(budget = 1_000, refill = 0.6))
        repeat(8) { base.enqueue(Step.Reply("r".repeat(350))) }
        val views = mutableListOf<ContextView>()
        repeat(8) { i ->
            chat.send("Message $i " + "x".repeat(340), device).toList()
            views += chat.context.value
        }
        val shownFrom = views.map { it.shownFrom }
        assertEquals("the first turns see everything", listOf(0, 0, 0), shownFrom.take(3))
        assertTrue("older messages were left out", shownFrom.last() > 0)
        assertTrue("starts only move forward, rarely", shownFrom.zipWithNext().count { (a, b) -> a != b } <= 2)
        views.forEach { assertTrue("${it.tokens} is within the budget", it.tokens <= 1_000) }
        base.requests.forEachIndexed { i, request ->
            assertEquals(views[i].messages - views[i].shownFrom + 1, request.transcript.size)
            assertEquals(views[i].trimmed, request.system!!.contains("This chat is long"))
        }
        // Between trims each request extends the one before, so the on-device cache keeps matching (ADR-033).
        val steady = (1 until 8).first { shownFrom[it] == shownFrom[it - 1] && shownFrom[it] > 0 }
        assertEquals(base.requests[steady - 1].transcript, base.requests[steady].transcript.take(base.requests[steady - 1].transcript.size))
        assertEquals("the whole chat stays saved", 16, chat.history().size)
    }

    @Test
    fun `each on-device turn teaches the calibration, and its scale sizes the window`() = runTest {
        val calibration = TokenCalibration()
        val chat = ChatSession(agent(mapOf(Tier.BASE to base)), tokens = calibration, window = ContextWindow(budget = 5_000))
        // ScriptedBrain counts one token per answer character, far more than the raw estimate of the turn.
        base.enqueue(Step.Reply("r".repeat(2_000)))
        chat.send("Hello " + "x".repeat(1_000), device).toList()
        val sample = chat.context.value.lastSample!!
        assertEquals(2_000, sample.counted)
        assertEquals(sample.counted.toDouble() / sample.estimated, calibration.ratio, 1e-9)

        base.enqueue(Step.Reply("ok"))
        chat.send("And now?", device).toList()
        val view = chat.context.value
        assertEquals(calibration.ratio * 1.05, view.scale, 1e-9)
        assertTrue("the prompt is counted in the runtime's tokens", view.tokens > Tokens.of(chat.history().take(2)))
    }

    @Test
    fun `memory commands are not offered to memory again`() = runTest {
        val offered = mutableListOf<String>()
        val chat = ChatSession(agent(mapOf(Tier.BASE to base)), onTurnCompleted = { text, _ -> offered += text })
        base.enqueue(Step.Reply("Noted."), Step.Reply("Done."), Step.Reply("Nice."))
        chat.send("Remember that I prefer mornings", device).toList()
        chat.send("Forget that I'm vegan", device).toList()
        chat.send("I just adopted a cat called Miso, and she is very curious about everything in the flat", device).toList()
        assertEquals(listOf("I just adopted a cat called Miso, and she is very curious about everything in the flat"), offered)
    }

    @Test
    fun `broken memory never breaks the chat`() = runTest {
        val chat = ChatSession(
            agent(mapOf(Tier.BASE to base)),
            memory = { error("database locked") },
            onTurnCompleted = { _, _ -> error("database locked") },
        )
        base.enqueue(Step.Reply("Hi!"))
        val events = chat.send("Hello", device).toList()
        assertTrue(events.last() is AgentEvent.Completed)
        assertEquals(null, base.requests.single().memory)
        assertEquals(2, chat.history().size)
    }

    @Test
    fun `a reply after reading texts keeps the messaging tools`() = runTest {
        val skills = mutableListOf<io.github.sandeepvijayarao09.anu.core.router.Skill>()
        val chat = ChatSession(agent(mapOf(Tier.BASE to base, Tier.PRO to pro)), toolsFor = { skills += it; emptyList() })
        repeat(3) { base.enqueue(Step.Reply("Okay.")); pro.enqueue(Step.Reply("Okay.")) }
        listOf("Any new texts from Maya?", "Reply that 1 works", "Make it 1:30").forEach { chat.send(it, device).toList() }
        assertEquals(List(3) { io.github.sandeepvijayarao09.anu.core.router.Skill.MESSAGES }, skills)
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
