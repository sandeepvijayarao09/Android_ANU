package io.github.sandeepvijayarao09.anu.core.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class WakeVoiceTest {

    private fun basis(index: Int) = FloatArray(SpeechFeatures.EMBEDDING_SIZE).also { it[index] = 1f }

    private fun frames(vararg ids: Int) = ids.map(::basis)

    @Test
    fun `the same sounds are at distance 0, and different ones at 1`() {
        assertEquals(0f, WakeDistance.of(WakeTemplate(frames(1, 2, 3)).frames, WakeTemplate(frames(1, 2, 3)).frames), 1e-6f)
        assertEquals(1f, WakeDistance.of(WakeTemplate(frames(1, 2, 3)).frames, WakeTemplate(frames(4, 5, 6)).frames), 1e-6f)
    }

    @Test
    fun `a slower phrase still matches, and it may begin anywhere but must end at the last frame`() {
        val template = WakeTemplate(frames(1, 2, 3)).frames
        assertEquals(0f, WakeDistance.of(template, WakeTemplate(frames(9, 9, 1, 1, 2, 2, 3, 3)).frames), 1e-6f)
        assertTrue(WakeDistance.of(template, WakeTemplate(frames(1, 2, 3, 9)).frames) > 0.1f)
    }

    @Test
    fun `the matcher finds the phrase as it ends in live audio`() {
        val voice = WakeVoice(listOf(WakeTemplate(frames(10, 11, 12))), threshold = 0.1f)
        val matcher = WakeMatcher(voice)
        val distances = listOf(1, 2, 3, 10, 11, 12, 4).map { matcher.push(basis(it)) }
        assertEquals(0f, distances[5], 1e-6f)
        assertTrue(distances.filterIndexed { i, _ -> i != 5 }.all { it > voice.threshold })
        matcher.reset()
        assertTrue(matcher.push(basis(12)) > voice.threshold)
    }

    @Test
    fun `a voice survives encoding, and unknown bytes are refused`() {
        val voice = WakeVoice(listOf(WakeTemplate(frames(1, 2)), WakeTemplate(frames(3))), threshold = 0.08f)
        val back = WakeVoice.decode(voice.encode())!!
        assertEquals(0.08f, back.threshold)
        assertEquals(2, back.templates.size)
        assertArrayEquals(voice.templates[0].frames[1], back.templates[0].frames[1], 0f)
        assertNull(WakeVoice.decode(byteArrayOf(0, 0, 0, 9)))
        assertNull(WakeVoice.decode(byteArrayOf()))
    }

    /** [seconds] of audio: silence, with a tone at [loudness] from [from] to [to] seconds. */
    private fun audio(seconds: Double, from: Double, to: Double, loudness: Double = 8_000.0) = ShortArray((seconds * 16_000).toInt()) { i ->
        val t = i / 16_000.0
        if (t in from..to) (loudness * sin(2 * Math.PI * 220 * t)).toInt().toShort() else 0
    }

    private fun embeddingsOf(audio: ShortArray) = List(audio.size / SpeechFeatures.CHUNK) { basis(it % 96) }

    @Test
    fun `a sample keeps the embeddings from just into the speech until just after it`() {
        val clip = audio(2.0, from = 0.5, to = 1.2)
        val sample = WakeEnrollment.sample(clip, embeddingsOf(clip)) as WakeEnrollment.Sample.Learned
        // Speech runs from sample 8000 to about 19200; kept are chunks ending from 11200 to 21760: k = 8 to 16.
        assertEquals(9, sample.template.frames.size)
        assertEquals(1f, sample.template.frames[0][8])
    }

    @Test
    fun `quiet, short and long samples are asked for again`() {
        val quiet = audio(2.0, 0.5, 1.2, loudness = 100.0)
        assertTrue((WakeEnrollment.sample(quiet, embeddingsOf(quiet)) as WakeEnrollment.Sample.Again).reason.startsWith("Didn't hear you"))
        val short = audio(2.0, 0.5, 0.6)
        assertTrue((WakeEnrollment.sample(short, embeddingsOf(short)) as WakeEnrollment.Sample.Again).reason.contains("short"))
        val long = audio(4.0, 0.5, 3.0)
        assertTrue((WakeEnrollment.sample(long, embeddingsOf(long)) as WakeEnrollment.Sample.Again).reason.contains("just"))
    }

    @Test
    fun `a room's steady sound isn't speech, and speech over it is found`() {
        val random = java.util.Random(1)
        val hum = ShortArray(64_000) { (random.nextGaussian() * 600).toInt().toShort() }
        assertTrue((WakeEnrollment.sample(hum, embeddingsOf(hum)) as WakeEnrollment.Sample.Again).reason.startsWith("Didn't hear you"))
        val tone = audio(4.0, from = 1.0, to = 1.6)
        val spoken = ShortArray(hum.size) { (hum[it] + tone[it]).toShort() }
        val span = WakeEnrollment.speech(spoken)!!
        assertTrue("$span", span.first in 15_800..16_200 && span.last in 25_400..25_800)
        assertTrue(WakeEnrollment.ended(List(5) { 0.45f } + List(4) { 0.8f } + List(8) { 0.47f }))
    }

    @Test
    fun `quieter talk half a second away is left out of the sample`() {
        val near = audio(4.0, from = 1.0, to = 1.6)
        val away = audio(4.0, from = 2.1, to = 3.5, loudness = 2_500.0)
        val both = ShortArray(near.size) { (near[it] + away[it]).toShort() }
        val span = WakeEnrollment.speech(both)!!
        assertTrue("$span", span.first in 15_800..16_200 && span.last in 25_400..25_800)
    }

    @Test
    fun `a sample unlike the others is refused, and the threshold follows the samples' spread within limits`() {
        val a = WakeTemplate(frames(1, 2, 3))
        assertTrue(WakeEnrollment.fits(a, emptyList()))
        assertTrue(WakeEnrollment.fits(WakeTemplate(frames(1, 2, 3)), listOf(a)))
        assertFalse(WakeEnrollment.fits(WakeTemplate(frames(7, 8, 9)), listOf(a)))
        assertEquals(WakeEnrollment.MIN_THRESHOLD, WakeEnrollment.voice(listOf(a, WakeTemplate(frames(1, 2, 3)))).threshold)
        assertEquals(WakeEnrollment.MAX_THRESHOLD, WakeEnrollment.voice(listOf(a, WakeTemplate(frames(7, 8, 9)))).threshold)
    }

    @Test
    fun `a sample's recording ends once speech is followed by quiet`() {
        val quiet = List(5) { 0.1f }
        assertFalse(WakeEnrollment.ended(quiet + quiet))
        assertFalse(WakeEnrollment.ended(quiet + List(4) { 0.6f } + List(5) { 0.1f }))
        assertTrue(WakeEnrollment.ended(quiet + List(4) { 0.6f } + List(8) { 0.1f }))
    }

    @Test
    fun `the stream feeds the mel model the latest audio and the embedding model the latest scaled frames`() {
        val melInputs = mutableListOf<FloatArray>()
        val embedInputs = mutableListOf<FloatArray>()
        val features = object : SpeechFeatures {
            override fun mel(samples: FloatArray): FloatArray {
                melInputs += samples.copyOf()
                return FloatArray(SpeechFeatures.MEL_FRAMES * SpeechFeatures.MEL_BINS) { samples.last() }
            }

            override fun embed(mel: FloatArray): FloatArray {
                embedInputs += mel.copyOf()
                return basis(0)
            }

            override fun close() = Unit
        }
        val stream = WakeStream(features)
        stream.push(ShortArray(SpeechFeatures.CHUNK) { 10 })
        stream.push(ShortArray(SpeechFeatures.CHUNK) { 20 })
        // 480 samples of the first chunk, then the second.
        assertEquals(10f, melInputs[1][0])
        assertEquals(20f, melInputs[1][480])
        assertEquals(SpeechFeatures.MEL_SAMPLES, melInputs[1].size)
        // Frames start at 1, as openWakeWord's do; new ones are scaled by /10 + 2 and go at the end.
        val mel = embedInputs[1]
        assertEquals(1f, mel[0])
        assertEquals(3f, mel[mel.size - 2 * 8 * 32])
        assertEquals(4f, mel.last())
    }
}
