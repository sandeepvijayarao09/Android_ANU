package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrainRequestTest {

    @Test
    fun `transcript must end with a user or tool turn`() {
        assertThrows(IllegalArgumentException::class.java) { BrainRequest(transcript = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            BrainRequest(transcript = listOf(ChatMessage.User("hi"), ChatMessage.Model("hello")))
        }
        BrainRequest(transcript = listOf(ChatMessage.User("hi")))
    }

    @Test
    fun `only the newest user message carries the time, even when tool turns follow it`() {
        val request = BrainRequest(
            transcript = listOf(
                ChatMessage.User("Any texts?"),
                ChatMessage.Model("None."),
                ChatMessage.User("Reply to Maya"),
                ChatMessage.Model("", listOf(ToolCall("messages_reply", JsonObject(emptyMap())))),
                ChatMessage.Tool(listOf(ToolResult("messages_reply", JsonObject(emptyMap())))),
            ),
            sentAt = "Fri 2026-09-25 14:46",
        )
        assertEquals(2, request.newestUserIndex)
        assertEquals("Any texts?", request.userText(0))
        assertEquals("[Fri 2026-09-25 14:46] Reply to Maya", request.userText(2))
        assertEquals("Reply to Maya", request.copy(sentAt = null).userText(2))
    }

    @Test
    fun `relevant facts travel with the newest message, and a cloud brain can leave them out`() {
        val request = BrainRequest(
            transcript = listOf(ChatMessage.User("Lunch ideas?"), ChatMessage.Model("Salads?"), ChatMessage.User("For Maya's birthday")),
            sentAt = "Sat 2026-09-26 12:00",
            relevant = "Facts:\n- Maya Chen · birthday: 12 March",
        )
        assertEquals("Lunch ideas?", request.userText(0))
        assertEquals("Facts:\n- Maya Chen · birthday: 12 March\n\n[Sat 2026-09-26 12:00] For Maya's birthday", request.userText(2))
        assertEquals("[Sat 2026-09-26 12:00] For Maya's birthday", request.userText(2, facts = null))
        assertEquals("$FACTS_HEADER\n- a\n- b", factsBlock(listOf("a", "b")))
        assertEquals(null, factsBlock(emptyList()))
    }
}
