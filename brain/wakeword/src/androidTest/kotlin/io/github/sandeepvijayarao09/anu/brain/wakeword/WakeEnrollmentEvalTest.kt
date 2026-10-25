package io.github.sandeepvijayarao09.anu.brain.wakeword

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandeepvijayarao09.anu.core.voice.SpeechFeatures
import io.github.sandeepvijayarao09.anu.core.voice.WakeEnrollment
import io.github.sandeepvijayarao09.anu.core.voice.WakeMatcher
import io.github.sandeepvijayarao09.anu.core.voice.WakeStream
import io.github.sandeepvijayarao09.anu.core.voice.WakeTemplate
import io.github.sandeepvijayarao09.anu.core.voice.WakeVoice
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Spike S16 (ADR-121): "Hey Anu" from enrolled samples, on LiteRT on the device. `scripts/wake-clips.sh <serial>`
 * pushes 12 macOS voices saying "Hey Anu" at three speeds and seven look-alike phrases. For each voice, the slow and
 * fast samples are enrolled, as the app would, and the middle speed must wake it while that voice's look-alikes
 * mustn't. Other voices' "Hey Anu" are counted, not judged: a voice of one's own may still answer to someone else.
 * Read `adb logcat -d -s AnuS16`. It skips itself without the clips.
 */
@RunWith(AndroidJUnit4::class)
class WakeEnrollmentEvalTest {
    private val dir = File("/data/local/tmp/anu/wake")
    private val random = Random(1)

    @Test
    fun enrolledSamplesWakeAndLookAlikesDont() {
        assumeTrue("Run scripts/wake-clips.sh first", dir.listFiles()?.any { it.name.startsWith("wake_") } == true)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val features = LiteRtSpeechFeatures.open(assets)
        val voices = dir.listFiles()!!.mapNotNull { Regex("wake_(\\d+)_150.wav").matchEntire(it.name)?.groupValues?.get(1) }.sorted()
        var woke = 0
        var falseWakes = 0
        var negatives = 0
        var otherWakes = 0
        var others = 0
        for (voice in voices) {
            val templates = listOf(150, 220).map { rate ->
                val audio = padded(read(File(dir, "wake_${voice}_$rate.wav")))
                val sample = WakeEnrollment.sample(audio, embeddings(features, audio))
                (sample as? WakeEnrollment.Sample.Learned)?.template ?: error("voice $voice at $rate: $sample")
            }
            val enrolled = WakeEnrollment.voice(templates)
            val held = closest(features, enrolled, read(File(dir, "wake_${voice}_185.wav")))
            if (held < enrolled.threshold) woke++
            val lookAlikes = dir.listFiles()!!.filter { it.name.startsWith("neg_${voice}_") }.sortedBy { it.name }
                .map { it.name to closest(features, enrolled, read(it)) }
            falseWakes += lookAlikes.count { it.second < enrolled.threshold }
            negatives += lookAlikes.size
            val otherVoices = voices.filter { it != voice }.map { closest(features, enrolled, read(File(dir, "wake_${it}_185.wav"))) }
            otherWakes += otherVoices.count { it < enrolled.threshold }
            others += otherVoices.size
            Log.i(
                TAG,
                "wake_eval voice=$voice frames=${templates.map { it.frames.size }} threshold=${f(enrolled.threshold)} " +
                    "held=${f(held)} lookalike_min=${f(lookAlikes.minOf { it.second })} (${lookAlikes.minBy { it.second }.first}) " +
                    "other_voice_min=${f(otherVoices.min())}",
            )
        }
        val timing = time(features)
        Log.i(TAG, "wake_eval summary woke=$woke/${voices.size} false_wakes=$falseWakes/$negatives other_voices_woke=$otherWakes/$others $timing")
        features.close()
        assertTrue("woke $woke of ${voices.size}", woke >= voices.size - 1)
        assertTrue("false wakes $falseWakes of $negatives", falseWakes <= 1)
    }

    /** The closest the clip comes to the voice, streamed a chunk at a time. */
    private fun closest(features: SpeechFeatures, voice: WakeVoice, clip: ShortArray): Float {
        val matcher = WakeMatcher(voice)
        return embeddings(features, padded(clip)).minOf { matcher.push(it) }
    }

    /** The clip with low noise around it, as the microphone would hear it. */
    private fun padded(clip: ShortArray): ShortArray =
        (noise(24_000) + clip.map { (it + gauss()).toInt().coerceIn(-32_768, 32_767).toShort() } + noise(12_800)).toShortArray()

    private fun embeddings(features: SpeechFeatures, audio: ShortArray): List<FloatArray> {
        val stream = WakeStream(features)
        return (0..audio.size - SpeechFeatures.CHUNK step SpeechFeatures.CHUNK).map { stream.push(audio.copyOfRange(it, it + SpeechFeatures.CHUNK)) }
    }

    /** Milliseconds per 80 ms chunk for the two models, and for matching against four samples. */
    private fun time(features: SpeechFeatures): String {
        val stream = WakeStream(features)
        val chunk = noise(SpeechFeatures.CHUNK).toShortArray()
        repeat(20) { stream.push(chunk) }
        val start = SystemClock.elapsedRealtimeNanos()
        val embeddings = List(200) { stream.push(chunk) }
        val models = (SystemClock.elapsedRealtimeNanos() - start) / 200 / 1e6
        val voice = WakeVoice(List(4) { WakeTemplate(embeddings.subList(it * 10, it * 10 + 10)) }, 0.1f)
        val matcher = WakeMatcher(voice)
        val matchStart = SystemClock.elapsedRealtimeNanos()
        embeddings.forEach { matcher.push(it) }
        val matching = (SystemClock.elapsedRealtimeNanos() - matchStart) / 200 / 1e6
        return "models_ms=${f(models.toFloat())} match_ms=${f(matching.toFloat())}"
    }

    private fun gauss() = random.nextGaussian() * 30

    private fun noise(samples: Int) = List(samples) { gauss().toInt().toShort() }

    /** 16-bit mono PCM from a WAV file's data chunk. */
    private fun read(file: File): ShortArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(12)
        while (bytes.remaining() >= 8) {
            val id = ByteArray(4).also { bytes.get(it) }.decodeToString()
            val size = bytes.int
            if (id == "data") {
                val samples = ShortArray(minOf(size, bytes.remaining()) / 2)
                bytes.asShortBuffer().get(samples)
                return samples
            }
            bytes.position(bytes.position() + size + size % 2)
        }
        error("${file.name} has no data")
    }

    private fun f(value: Float) = "%.3f".format(value)

    private companion object {
        const val TAG = "AnuS16"
    }
}
