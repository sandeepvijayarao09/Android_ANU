package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Photo
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {

    // 350 characters are 100 tokens, plus 4 for the turn markers: 104 a message, 208 an exchange.
    private fun user() = ChatMessage.User("u".repeat(350))
    private fun model() = ChatMessage.Model("m".repeat(350))
    private fun exchanges(count: Int) = (0 until count).flatMap { listOf(user(), model()) }

    @Test
    fun `everything that fits is shown`() {
        assertEquals(0, ContextWindow(budget = 1_000).start(exchanges(3), previous = 0, fixed = 100))
        assertEquals(0, ContextWindow().start(exchanges(500), previous = 0, fixed = 100))
    }

    @Test
    fun `over budget, the oldest whole exchanges go, down to the refill share`() {
        // 100 + 5 × 208 = 1,140 is over 1,000. Down to 600: 100 + 2 × 208 = 516 fits, so two exchanges stay.
        assertEquals(6, ContextWindow(budget = 1_000, refill = 0.6).start(exchanges(5), previous = 0, fixed = 100))
    }

    @Test
    fun `a tool call is never separated from its result`() {
        val call = ChatMessage.Model("", listOf(ToolCall("calendar_list_events", buildJsonObject { put("day", "d".repeat(300)) })))
        val result = ChatMessage.Tool(listOf(ToolResult("calendar_list_events", buildJsonObject { put("events", "e".repeat(300)) })))
        val history = listOf(user(), call, result, model(), user(), model())
        val start = ContextWindow(budget = 600, refill = 0.9).start(history, previous = 0, fixed = 50)
        assertEquals("the first exchange goes whole, tool call and result included", 4, start)
    }

    @Test
    fun `the start stays put while the chat fits, then moves several exchanges at once`() {
        val window = ContextWindow(budget = 1_000, refill = 0.6)
        val history = mutableListOf<ChatMessage>()
        val starts = mutableListOf<Int>()
        var start = 0
        repeat(12) {
            start = window.start(history, start, fixed = 100)
            starts += start
            assertTrue("a start is always at a user message", start == history.size || history[start] is ChatMessage.User)
            assertTrue("what is shown fits", 100 + Tokens.of(history.subList(start, history.size)) <= 1_000)
            history += user()
            history += model()
        }
        // Four old exchanges fit (100 + 4 × 208 = 932). The fifth trims down to two, and so on.
        assertEquals(listOf(0, 0, 0, 0, 0, 6, 6, 6, 12, 12, 12, 18), starts)
    }

    @Test
    fun `a calibrated scale counts in the runtime's tokens`() {
        // At twice the raw estimate, 100 + 3 × 208 = 724 raw is 1,448: over 1,000. Down to 700, one exchange
        // stays: (100 + 208) × 2 = 616.
        assertEquals(0, ContextWindow(budget = 1_000).start(exchanges(3), previous = 0, fixed = 100, scale = 1.0))
        assertEquals(4, ContextWindow(budget = 1_000, refill = 0.7).start(exchanges(3), previous = 0, fixed = 100, scale = 2.0))
    }

    @Test
    fun `if not even the newest exchange fits, only the new message is sent`() {
        val huge = listOf(ChatMessage.User("u".repeat(3_500)), ChatMessage.Model("m".repeat(3_500)))
        assertEquals(2, ContextWindow(budget = 500).start(huge, previous = 0, fixed = 100))
    }

    @Test
    fun `after exchanges are taken back, a start past the end fits the window afresh`() {
        assertEquals(0, ContextWindow(budget = 1_000).start(exchanges(2), previous = 10, fixed = 100))
    }

    @Test
    fun `tokens are counted on the safe side, and JSON costs more than prose`() {
        assertEquals(0, Tokens.of(null as String?))
        assertEquals(29, Tokens.of("a".repeat(100)))
        val arguments = buildJsonObject { put("q", "a".repeat(100)) }
        val call = ChatMessage.Model("", listOf(ToolCall("x_y", arguments)))
        // 4 for the turn markers, 2 for the name, and the arguments at 2.5 characters a token.
        assertEquals(4 + 2 + kotlin.math.ceil(arguments.toString().length / 2.5).toInt(), Tokens.of(call))
    }

    @Test
    fun `pictures in view stay within what the engine takes, by whole exchanges`() {
        fun withPhotos(n: Int) = ChatMessage.User("look", List(n) { Photo(it + 1L) })
        val history = listOf(withPhotos(2), model(), withPhotos(1), model(), user(), model())
        val window = ContextWindow()
        assertEquals("three in view and one more fits", 0, window.fitPictures(history, from = 0, adding = 1))
        assertEquals("two more would make five: the first exchange goes", 2, window.fitPictures(history, from = 0, adding = 2))
        assertEquals("four new ones leave no photo before them", 4, window.fitPictures(history, from = 0, adding = 4))
        assertEquals("it never moves back", 4, window.fitPictures(history, from = 4, adding = 0))
        assertEquals("more than it takes leaves only the new message", history.size, window.fitPictures(history, from = 0, adding = 5))
    }
}
