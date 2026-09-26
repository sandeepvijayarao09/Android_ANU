package io.github.sandeepvijayarao09.anu.connector.messages

import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.RunState
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.agent.ToolOutcome
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MessagingTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val store = ConversationStore(maxConversations = 2, maxLines = 3)
    private val sent = mutableListOf<String>()
    private val reply = ReplySender { sent += it }
    private val ctx = ToolContext("run", "key")

    private fun line(sender: String, text: String, minute: Long, fromMe: Boolean = false) =
        ChatLine(sender, text, Instant.parse("2026-09-25T18:00:00Z").plusSeconds(minute * 60), fromMe)

    private fun snapshot(key: String, vararg lines: ChatLine, reply: ReplySender? = this.reply) =
        ConversationSnapshot(key, "com.google.android.apps.messaging", "Messages", "Maya", false, lines.toList(), reply)

    @Test
    fun `repeated notifications merge without duplicates and keep the newest lines`() {
        store.update(snapshot("m:1", line("Maya", "Lunch?", 1)))
        store.update(snapshot("m:1", line("Maya", "Lunch?", 1), line("Maya", "At 1?", 2)))
        store.update(snapshot("m:1", line("You", "Sure", 3, fromMe = true), line("Maya", "Great", 4)))
        assertEquals(listOf("At 1?", "Sure", "Great"), store.get("m:1")!!.lines.map { it.text })
    }

    @Test
    fun `only the newest conversations are kept, and a dismissed notification can't be replied to`() {
        store.update(snapshot("a", line("A", "1", 1)))
        store.update(snapshot("b", line("B", "2", 2)))
        store.update(snapshot("c", line("C", "3", 3)))
        assertEquals(listOf("c", "b"), store.list().map { it.key })
        store.detachReply("c")
        assertNull(store.get("c")!!.reply)
        assertEquals("3", store.get("c")!!.lines.single().text) // the text stays
    }

    @Test
    fun `listing and reading mask verification codes`() = runTest {
        store.update(snapshot("m:1", line("Bank", "Your verification code is 482913", 1)))
        val listed = ListConversationsTool(store) { zone }.invoke(ListConversationsInput(), ctx)
        assertEquals("Your verification code is ••••••", listed.conversations.single().lastMessage)
        val read = ReadConversationTool(store) { zone }.invoke(ReadConversationInput("m:1"), ctx)
        assertTrue(read.messages.none { "482913" in it.text })
        assertEquals("2026-09-25T11:01", read.messages.single().at)
    }

    @Test
    fun `a reply is external, names the chat, and goes through the notification's reply button`() = runTest {
        store.update(snapshot("m:1", line("Maya", "Lunch at 1?", 1)))
        val tool = ReplyTool(store)
        val input = ReplyInput("m:1", "Yes, see you at 1")
        assertEquals(RiskTier.WRITE_EXTERNAL, tool.risk(input))
        assertEquals("Reply in Messages to \"Maya\": \"Yes, see you at 1\"", tool.describe(input))
        tool.invoke(input, ctx)
        assertEquals(listOf("Yes, see you at 1"), sent)
    }

    @Test
    fun `the broker asks before replying, and refuses unknown chats before asking`() = runTest {
        store.update(snapshot("m:1", line("Maya", "Lunch?", 1)))
        val asked = mutableListOf<String>()
        val broker = ToolBroker(listOf(ReplyTool(store)), ApprovalGate { asked += it.summary; ApprovalDecision.Approved })
        val run = RunState("r", "t", BrainLocation.ON_DEVICE, false)

        val unknown = broker.execute(ToolCall("messages_reply", buildJsonObject { put("conversationId", "nope"); put("text", "hi") }), run)
        assertTrue(unknown is ToolOutcome.Invalid)
        assertTrue(asked.isEmpty())

        broker.execute(ToolCall("messages_reply", buildJsonObject { put("conversationId", "m:1"); put("text", "On my way") }), run)
        assertEquals(listOf("Reply in Messages to \"Maya\": \"On my way\""), asked)
        assertEquals(listOf("On my way"), sent)
    }

    @Test
    fun `a chat can be named by its title or phone number, but only when one chat matches`() {
        val chats = ConversationStore()
        fun chat(key: String, title: String) =
            chats.update(ConversationSnapshot(key, "app", "Messages", title, false, listOf(line(title, "hi", 1)), reply))
        chat("m:1", "(555) 123-4567")
        chat("m:2", "Maya Chen")
        chat("m:3", "Maya Patel")
        assertEquals("m:1", chats.find("m:1")?.key)
        assertEquals("m:1", chats.find("+1 555-123-4567")?.key)
        assertEquals("m:1", chats.find("5551234567")?.key)
        assertEquals("m:2", chats.find("maya chen")?.key)
        assertEquals("m:3", chats.find("Patel")?.key)
        assertNull(chats.find("Maya")) // two chats match, so neither is guessed
        assertNull(chats.find("555")) // too few digits to be a number
    }

    @Test
    fun `an unknown chat is refused with the chats the model can use`() = runTest {
        store.update(snapshot("m:1", line("Maya", "Lunch?", 1)))
        val outcome = ToolBroker(listOf(ReplyTool(store)), ApprovalGate { ApprovalDecision.Approved })
            .execute(ToolCall("messages_reply", buildJsonObject { put("conversationId", "Raj"); put("text", "hi") }), RunState("r", "t", BrainLocation.ON_DEVICE, false))
        assertTrue(outcome.toString(), outcome is ToolOutcome.Invalid && "\"Maya\" (Messages, conversationId m:1)" in outcome.toString())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a chat whose notification is gone explains how to reply instead`() = runTest {
        store.update(snapshot("m:1", line("Maya", "Lunch?", 1), reply = null))
        val outcome = ToolBroker(listOf(ReplyTool(store)), ApprovalGate { ApprovalDecision.Approved })
            .execute(ToolCall("messages_reply", buildJsonObject { put("conversationId", "m:1"); put("text", "hi") }), RunState("r", "t", BrainLocation.ON_DEVICE, false))
        assertTrue(outcome.toString(), outcome is ToolOutcome.Failed && "Open Messages to reply" in outcome.reason)
    }
}
