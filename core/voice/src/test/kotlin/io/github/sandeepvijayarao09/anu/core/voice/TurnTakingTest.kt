package io.github.sandeepvijayarao09.anu.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTakingTest {

    @Test
    fun `a finished-sounding request ends after a short pause, not the recognizer's 2 s (ADR-129)`() {
        val end = Endpointer()
        end.level(0.05f, 0)
        end.partial("what's on my", 400)
        end.partial("what's on my calendar today", 900)
        end.level(0.8f, 1_000)
        assertFalse(end.done(1_600))
        assertTrue(end.done(1_000 + Endpointer.QUICK_MS))
    }

    @Test
    fun `a request that trails off waits the long pause`() {
        val end = Endpointer()
        end.partial("remind me to", 500)
        assertFalse(end.done(500 + Endpointer.QUICK_MS))
        assertFalse(end.done(500 + Endpointer.SLOW_MS - 1))
        assertTrue(end.done(500 + Endpointer.SLOW_MS))
        assertEquals(Endpointer.Sounds.UNFINISHED, Endpointer.sounds("set a timer for um"))
        assertEquals(Endpointer.Sounds.UNFINISHED, Endpointer.sounds("text Maya and"))
        assertEquals(Endpointer.Sounds.UNFINISHED, Endpointer.sounds("set"))
    }

    @Test
    fun `one-word answers are whole, and nothing ends before a word is heard`() {
        assertEquals(Endpointer.Sounds.FINISHED, Endpointer.sounds("Yes."))
        assertEquals(Endpointer.Sounds.FINISHED, Endpointer.sounds("what should I do"))
        assertEquals(Endpointer.Sounds.FINISHED, Endpointer.sounds("what's the weather like"))
        val end = Endpointer()
        end.level(0.9f, 100)
        assertFalse(end.done(10_000))
    }

    @Test
    fun `still talking keeps the listen open`() {
        val end = Endpointer()
        end.level(0.05f, 0)
        end.partial("call mom", 500)
        // Loud but no new words yet: the user is mid-word. Two words wait the middle pause, since a thought often goes on.
        end.level(0.7f, 1_100)
        assertFalse(end.done(1_100 + Endpointer.QUICK_MS))
        assertTrue(end.done(1_100 + Endpointer.MID_MS))
    }

    @Test
    fun `talking over Anu's voice fires, its own echo doesn't (ADR-129)`() {
        val detector = BargeInDetector()
        // Anu's own voice after echo cancelling, with the engine's start silent.
        repeat(15) { assertFalse(detector.push(0.1f)) }
        repeat(35) { assertFalse(detector.push(if (it % 3 == 0) 0.25f else 0.38f)) }
        assertEquals(0.48f, detector.threshold!!, 0.001f)
        // A loud word of its own is short.
        repeat(8) { assertFalse(detector.push(0.6f)) }
        repeat(20) { assertFalse(detector.push(0.3f)) }
        // The user, for 320 ms of the last 400.
        var fired = -1
        for (k in 0 until 30) if (detector.push(0.62f) && fired < 0) fired = k
        assertEquals(15, fired)
    }

    @Test
    fun `a quiet echo still needs someone talking to the phone`() {
        val detector = BargeInDetector()
        repeat(50) { detector.push(0.05f) }
        assertEquals(BargeInDetector.MIN_LEVEL, detector.threshold!!, 0.001f)
        repeat(40) { assertFalse(detector.push(0.4f)) }
    }

    @Test
    fun `before it has heard Anu's voice it never fires`() {
        val detector = BargeInDetector()
        repeat(49) { assertFalse(detector.push(1f)) }
        assertNull(detector.threshold)
    }

    @Test
    fun `Anu speaks with the clearest voice on the phone, never a network one (ADR-129)`() {
        fun v(name: String, country: String, quality: Int, latency: Int = 300, network: Boolean = false, installed: Boolean = true, language: String = "en") =
            TtsVoice(name, language, country, quality, latency, network, installed)
        val voices = listOf(
            v("en-us-x-network", "US", 500, network = true),
            v("en-us-x-local-low", "US", 300),
            v("en-us-x-local-high", "US", 400, latency = 200),
            v("en-us-x-local-high-slow", "US", 400, latency = 400),
            v("en-gb-x-local", "GB", 500),
            v("en-us-x-missing", "US", 500, installed = false),
            v("de-de-x-local", "DE", 500, language = "de"),
        )
        assertEquals("en-us-x-local-high", VoicePick.best(voices, "en", "US")?.name)
        assertEquals("en-gb-x-local", VoicePick.best(voices, "en", "IN")?.name)
        assertNull(VoicePick.best(voices.filter { it.needsNetwork }, "en", "US"))
    }

    @Test
    fun `an answer's first clause is spoken before its sentence ends (ADR-129)`() {
        val text = "Your next meeting is with Maya at three, and after that you're free"
        val (first, at) = Sentences.next(text, 0, final = false, first = true)
        assertEquals(listOf("Your next meeting is with Maya at three,"), first)
        // Later pieces wait for whole sentences.
        assertEquals(emptyList<String>(), Sentences.next(text, at, final = false, first = false).first)
        // A short opening clause waits for more.
        assertEquals(emptyList<String>(), Sentences.next("Sure, your next", 0, final = false, first = true).first)
        assertEquals(listOf("Sure!"), Sentences.next("Sure! Your next, one", 0, final = false, first = true).first)
    }

    @Test
    fun `list numbers and titles don't end a sentence`() {
        assertEquals(listOf("Call Dr. Chen at noon."), Sentences.next("Call Dr. Chen at noon.", 0, final = false).first)
        assertEquals(listOf("1. Milk", "2. Eggs"), Sentences.next("1. Milk\n2. Eggs\n", 0, final = false).first)
        assertEquals(listOf("The answer is no.", "Okay?"), Sentences.next("The answer is no. Okay?", 0, final = false).first)
    }
}
