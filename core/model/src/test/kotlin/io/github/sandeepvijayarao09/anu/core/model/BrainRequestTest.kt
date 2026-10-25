package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `the week's dates travel with the newest message, after the facts and before the time`() {
        val request = BrainRequest(
            transcript = listOf(ChatMessage.User("Lunch?"), ChatMessage.Model("Sure."), ChatMessage.User("Book it tomorrow")),
            sentAt = "Fri 2026-09-25 23:58",
            dates = "today Fri 2026-09-25; tomorrow Sat 2026-09-26",
            relevant = "Facts:\n- likes sushi",
        )
        assertEquals("Lunch?", request.userText(0))
        assertEquals(
            "Facts:\n- likes sushi\n\nDates: today Fri 2026-09-25; tomorrow Sat 2026-09-26.\n[Fri 2026-09-25 23:58] Book it tomorrow",
            request.userText(2),
        )
        assertTrue(request.echoesFraming("Dates: today Fri 2026-09-25; tomorrow Sat 2026-09-26. Booked."))
        assertFalse(request.echoesFraming("Booked for tomorrow, Sat 2026-09-26."))
        assertEquals("Booked.", request.withoutFraming("Dates: today Fri 2026-09-25; tomorrow Sat 2026-09-26. Booked."))
    }

    @Test
    fun `an answer that quotes the framing is spotted, and the framing can be cut from it`() {
        val request = BrainRequest(transcript = listOf(ChatMessage.User("Plan a dinner")), sentAt = "Sun 2026-09-27 01:32")
        assertTrue(request.echoesFraming("You mentioned: \"[Sun 2026-09-27 01:32] Plan a dinner.\""))
        assertTrue(request.echoesFraming("$FACTS_HEADER diet: vegetarian"))
        assertFalse("the date alone is fine", request.echoesFraming("It's Sun 2026-09-27 01:32 now."))
        assertFalse(request.copy(sentAt = null).echoesFraming("[Sun 2026-09-27 01:32] Plan a dinner"))
        assertEquals("Plan a dinner: try a risotto.", request.withoutFraming("[Sun 2026-09-27 01:32] Plan a dinner: try a risotto."))
        assertEquals("diet: vegetarian. Try a risotto.", request.withoutFraming("$FACTS_HEADER diet: vegetarian. Try a risotto."))
    }

    @Test
    fun `Anu's note ends the newest message, never earlier ones, and quoting it is caught`() {
        val request = BrainRequest(
            transcript = listOf(ChatMessage.User("Hi"), ChatMessage.Model("Hello!"), ChatMessage.User("I'm vegetarian, keep that in mind")),
            sentAt = "Tue 2026-09-29 10:40",
            relevant = "Facts:\n- diet: vegetarian",
            note = "Don't say \"noted\".",
        )
        assertEquals("Hi", request.userText(0))
        assertEquals(
            "Facts:\n- diet: vegetarian\n\n[Tue 2026-09-29 10:40] I'm vegetarian, keep that in mind\n\n$NOTE_HEADER Don't say \"noted\".",
            request.userText(2),
        )
        assertEquals("the cloud gets it too", "[Tue 2026-09-29 10:40] I'm vegetarian, keep that in mind\n\n$NOTE_HEADER Don't say \"noted\".", request.userText(2, facts = null))
        assertTrue(request.echoesFraming("$NOTE_HEADER Don't say \"noted\". Thanks!"))
        assertFalse("without a note, the words are fine", request.copy(note = null).echoesFraming("$NOTE_HEADER hello"))
        assertEquals("Thanks!", request.withoutFraming("$NOTE_HEADER Don't say \"noted\". Thanks!"))
    }
}
