package io.github.sandeepvijayarao09.anu.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeListenerTest {

    /** Plays one scripted round per listen(), each taking [roundMillis] of virtual time. */
    private class ScriptedSpeech(private val rounds: List<SpeechEvent>, private val roundMillis: Long = 3_000) : SpeechInput {
        var listens = 0
        override val available = true
        override fun listen(): Flow<SpeechEvent> = flow {
            val event = rounds.getOrElse(listens) { SpeechEvent.Error("Didn't catch that.", heardNothing = true) }
            listens++
            emit(SpeechEvent.Listening)
            delay(roundMillis)
            emit(event)
        }
    }

    private fun TestScope.listener(speech: SpeechInput) = WakeListener(speech, clock = { currentTime })

    @Test
    fun `the request after the wake phrase opens Anu, and other speech is ignored`() = runTest {
        val speech = ScriptedSpeech(
            listOf(
                SpeechEvent.Final("a new message arrived"),
                SpeechEvent.Final("Hey Anu, what's on my calendar?"),
                SpeechEvent.Final("Anu"),
            ),
        )
        val woke = mutableListOf<String?>()
        val job = launch { listener(speech).run { woke += it; true } }
        advanceTimeBy(20_000)
        job.cancel()
        assertEquals(listOf("what's on my calendar?", null), woke)
    }

    @Test
    fun `when Anu can't open, listening stops`() = runTest {
        val speech = ScriptedSpeech(listOf(SpeechEvent.Final("hey anu")))
        assertEquals(WakeListener.Stop.CantOpen, listener(speech).run { false })
    }

    @Test
    fun `a missing language pack stops at once`() = runTest {
        val speech = ScriptedSpeech(listOf(SpeechEvent.Error("No pack", needsLanguagePack = true)))
        assertEquals(WakeListener.Stop.Failed("No pack"), listener(speech).run { true })
        assertEquals(1, speech.listens)
    }

    @Test
    fun `other errors back off, then stop after five in a row`() = runTest {
        val busy = SpeechEvent.Error("The microphone is busy.")
        val speech = ScriptedSpeech(List(5) { busy })
        val stop = listener(speech).run { true }
        assertEquals(WakeListener.Stop.Failed("The microphone is busy."), stop)
        // 5 rounds of 3 s, backoffs of 2 + 4 + 8 + 16 s, and 4 short gaps.
        assertEquals(5 * 3_000L + 30_000L + 4 * 250L, currentTime)
    }

    @Test
    fun `silence keeps listening without counting as a failure`() = runTest {
        val silence = SpeechEvent.Error("Didn't catch that.", heardNothing = true)
        val speech = ScriptedSpeech(List(20) { silence })
        val job = launch { listener(speech).run { true } }
        advanceTimeBy(60_000)
        job.cancel()
        assertTrue("listened ${speech.listens} times", speech.listens >= 15)
    }

    @Test
    fun `a recognizer that ends every round at once is paused, not spun`() = runTest {
        val speech = ScriptedSpeech(emptyList(), roundMillis = 0)
        val job = launch { listener(speech).run { true } }
        advanceTimeBy(60_000)
        job.cancel()
        // Each 3 quick rounds cost 3 short gaps and a 5 s pause: about 11 rounds a minute, not thousands.
        assertTrue("listened ${speech.listens} times", speech.listens in 20..40)
    }
}
