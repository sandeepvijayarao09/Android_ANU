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
    private val session = ChatSession(Cascade(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)[it] }))
    private val device = DeviceState(online = true, availableTiers = Tier.entries.toSet())

    @Test
    fun `completed turns join the transcript and are sent as history next time`() = runTest {
        base.enqueue(Step.Reply("Hi!"), Step.Reply("Sure."))
        session.send("Hello", device).toList()
        session.send("Remind me later", device).toList()
        assertEquals(
            listOf(ChatMessage.User("Hello"), ChatMessage.Model("Hi!"), ChatMessage.User("Remind me later"), ChatMessage.Model("Sure.")),
            session.messages,
        )
        assertEquals(3, base.requests[1].transcript.size)
    }

    @Test
    fun `a turn blocked for consent leaves the transcript unchanged, and consent resends it`() = runTest {
        val cloudOnly = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        val blocked = session.send("Draft my resignation letter", cloudOnly).toList()
        assertTrue(blocked.single() is CascadeEvent.ConsentNeeded)
        assertTrue(session.messages.isEmpty())

        cloud.enqueue(Step.Reply("Here is a draft."))
        session.send("Draft my resignation letter", cloudOnly, cloudConsented = true).toList()
        assertEquals(2, session.messages.size)
    }

    @Test
    fun `retry with Pro replaces the last exchange`() = runTest {
        base.enqueue(Step.Reply("meh"))
        pro.enqueue(Step.Reply("better"))
        session.send("Explain tides", device).toList()
        val text = session.takeBackLast()!!
        val events = session.send(text, device, pinned = Tier.PRO).toList()
        assertEquals(Tier.PRO, (events.last() as CascadeEvent.Completed).tier)
        assertEquals(listOf(ChatMessage.User("Explain tides"), ChatMessage.Model("better")), session.messages)
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
