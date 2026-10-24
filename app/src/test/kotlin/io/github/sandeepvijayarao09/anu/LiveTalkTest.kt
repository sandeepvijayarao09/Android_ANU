package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.feature.home.Bubble
import io.github.sandeepvijayarao09.anu.feature.home.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTalkTest {

    @Test
    fun `live talk's status follows the Talk page, and is null when live talk is off`() {
        assertNull(liveStatus(ChatUiState(listening = true)))
        assertEquals(LivePhase.LISTENING, liveStatus(ChatUiState(live = true, listening = true, heard = "what's "))?.phase)
        assertEquals(LivePhase.THINKING, liveStatus(ChatUiState(live = true, busy = true, heard = "What's on today?"))?.phase)
        val speaking = liveStatus(ChatUiState(live = true, speaking = true, bubbles = listOf(Bubble(1, true, "Hi"), Bubble(2, false, "Your day is clear.\nEnjoy it."))))
        assertEquals(LiveStatus(LivePhase.SPEAKING, answer = "Your day is clear.\nEnjoy it."), speaking)
        assertEquals("a consent sheet waits, whatever else is on", LivePhase.WAITING, liveStatus(ChatUiState(live = true, busy = true, consentFor = "Ask Gemini"))?.phase)
        assertEquals("between an answer and the next listen", LivePhase.LISTENING, liveStatus(ChatUiState(live = true))?.phase)
    }

    @Test
    fun `the notification and the pill say what Anu is doing, in one line`() {
        assertEquals(LiveWords("Anu · Listening", "Say something, or tap Stop", "Live talk with Anu: listening. Say something, or tap Stop"), liveWords(LiveStatus(LivePhase.LISTENING)))
        assertEquals("what's on", liveWords(LiveStatus(LivePhase.LISTENING, heard = "what's on")).line)
        assertEquals("“What's on today?”", liveWords(LiveStatus(LivePhase.THINKING, heard = "What's on today?")).line)
        val spoken = liveWords(LiveStatus(LivePhase.SPEAKING, answer = "Your day is clear.\nEnjoy it."))
        assertEquals("Anu · Speaking" to "Your day is clear.", spoken.heading to spoken.line)
        assertEquals("Anu · Answering", liveWords(LiveStatus(LivePhase.SPEAKING, answer = "Hi", muted = true)).heading)
        assertEquals("Open Anu to approve or decline", liveWords(LiveStatus(LivePhase.WAITING)).line)
    }

    @Test
    fun `live talk goes on behind another app only with something the user can see and stop`() {
        assertNull(liveBlocked(serviceRunning = true, pill = true, notifications = false))
        assertNull(liveBlocked(serviceRunning = true, pill = false, notifications = true))
        assertEquals(LIVE_NEEDS_NOTIFICATIONS, liveBlocked(serviceRunning = true, pill = false, notifications = false))
        assertEquals("without the microphone service Android would silence it", LIVE_LEFT, liveBlocked(serviceRunning = false, pill = true, notifications = true))
    }

    @Test
    fun `the tile reads through app control first, then the assistant, else says what to turn on`() {
        assertEquals(TileRoute.Read, tileRoute(hasReader = true, readerOn = true, assistant = true))
        assertEquals(TileRoute.Assistant, tileRoute(hasReader = true, readerOn = false, assistant = true))
        assertEquals(TileRoute.Assistant, tileRoute(hasReader = false, readerOn = false, assistant = true))
        assertEquals(TileRoute.Explain(TILE_TURN_ON), tileRoute(hasReader = true, readerOn = false, assistant = false))
        assertEquals("the Play build has no app control", TileRoute.Explain(TILE_ASSISTANT_FIRST), tileRoute(hasReader = false, readerOn = false, assistant = false))
    }

    @Test
    fun `a screen's password fields, and its codes when it speaks of one, are masked`() {
        val plain = ScreenSecrets.find(listOf("Order 482913 shipped" to 1, "Hello" to 2), secrets = listOf(9))
        assertEquals("a password field always, a number with no code talk never", listOf(9), plain.hidden)
        assertEquals(emptyList<String>(), plain.codes)

        val coded = ScreenSecrets.find(listOf("Your verification code" to 1, "482913" to 2, "Hello" to 3), secrets = emptyList())
        assertEquals(listOf(2), coded.hidden)
        assertEquals(listOf("482913"), coded.codes)
    }
}
