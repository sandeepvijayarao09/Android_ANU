package io.github.sandeepvijayarao09.anu.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class WakeDetectorTest {

    /** Each chunk carries a sound's id in its samples; its embedding is that id's basis vector. */
    private class IdFeatures : SpeechFeatures {
        var closed = false

        override fun mel(samples: FloatArray) = FloatArray(SpeechFeatures.MEL_FRAMES * SpeechFeatures.MEL_BINS) { samples.last() }

        // Scaled by /10 + 2 in the stream, so the id comes back out.
        override fun embed(mel: FloatArray) = basis(((mel.last() - 2f) * 10f).roundToInt())

        override fun close() {
            closed = true
        }
    }

    /** Plays [rounds] of ids, one per opening of the microphone, 80 ms a chunk; after them it's quiet (id 0). */
    private class ScriptedMic(private val rounds: List<List<Int>>) : MicInput {
        var opened = 0
        override fun chunks(size: Int): Flow<ShortArray> = flow {
            val ids = rounds.getOrElse(opened) { emptyList() }
            opened++
            for (id in ids + List(1_000) { 0 }) {
                delay(80)
                emit(ShortArray(size) { id.toShort() })
            }
        }
    }

    private companion object {
        fun basis(index: Int) = FloatArray(SpeechFeatures.EMBEDDING_SIZE).also { it[index] = 1f }

        val VOICE = WakeVoice(listOf(WakeTemplate(listOf(basis(5), basis(6), basis(7)))), threshold = 0.1f)
    }

    @Test
    fun `the phrase wakes Anu once with no request, and other sounds don't`() = runTest {
        val mic = ScriptedMic(listOf(listOf(1, 2, 3, 5, 6, 7, 8, 9), listOf(1, 2, 3)))
        val wakes = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        val job = launch { WakeDetector(mic, ::IdFeatures, VOICE, log = { logs += it }, work = EmptyCoroutineContext).run { wakes += it; true } }
        advanceTimeBy(20_000)
        job.cancel()
        assertEquals(listOf<String?>(null), wakes)
        // The microphone closed for the wake, then opened again.
        assertEquals(2, mic.opened)
        assertTrue(logs.single(), logs.single().startsWith("Hey Anu: woke at 0.000"))
    }

    @Test
    fun `when Anu can't open, it stops, and the models are closed`() = runTest {
        val features = IdFeatures()
        val stop = WakeDetector(ScriptedMic(listOf(listOf(5, 6, 7))), { features }, VOICE, work = EmptyCoroutineContext).run { false }
        assertEquals(WakeListener.Stop.CantOpen, stop)
        assertTrue(features.closed)
    }

    @Test
    fun `a busy microphone is tried again with growing pauses, then it stops`() = runTest {
        val busy = object : MicInput {
            var tries = 0
            override fun chunks(size: Int): Flow<ShortArray> = flow {
                tries++
                throw MicUnavailable("The microphone is in use by another app.")
            }
        }
        val stop = WakeDetector(busy, ::IdFeatures, VOICE, work = EmptyCoroutineContext).run { true }
        assertEquals(WakeListener.Stop.Failed("The microphone is in use by another app."), stop)
        assertEquals(5, busy.tries)
        assertEquals(2_000L + 4_000 + 8_000 + 16_000, currentTime)
    }
}
