package io.github.sandeepvijayarao09.anu.core.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/**
 * openWakeWord's two shared feature models (ADR-121): a mel spectrogram, then Google's `speech_embedding`, which turns
 * 0.76 s of it into 96 numbers every 80 ms. They run on LiteRT in `brain:wakeword`; this contract keeps the matching
 * here in plain Kotlin.
 */
interface SpeechFeatures : AutoCloseable {
    /** [MEL_FRAMES] × [MEL_BINS] values from [MEL_SAMPLES] samples of 16-bit audio, as floats not scaled to ±1. */
    fun mel(samples: FloatArray): FloatArray

    /** One [EMBEDDING_SIZE] embedding from [EMBEDDING_WINDOW] × [MEL_BINS] mel values. */
    fun embed(mel: FloatArray): FloatArray

    companion object {
        const val RATE = 16_000

        /** 80 ms: one embedding per chunk. */
        const val CHUNK = 1_280

        /** A chunk and the 30 ms before it, which the mel model needs for its first frames. */
        const val MEL_SAMPLES = 1_760
        const val MEL_FRAMES = 8
        const val MEL_BINS = 32
        const val EMBEDDING_WINDOW = 76
        const val EMBEDDING_SIZE = 96
    }
}

/**
 * Turns audio into embeddings as it arrives, one per [SpeechFeatures.CHUNK], as openWakeWord streams it: the mel of
 * the last [SpeechFeatures.MEL_SAMPLES] samples, scaled as its models expect, and the embedding of the last
 * [SpeechFeatures.EMBEDDING_WINDOW] mel frames.
 */
class WakeStream(private val features: SpeechFeatures) {
    private val raw = FloatArray(SpeechFeatures.MEL_SAMPLES)
    private val mel = FloatArray(SpeechFeatures.EMBEDDING_WINDOW * SpeechFeatures.MEL_BINS) { 1f }

    fun push(chunk: ShortArray): FloatArray {
        require(chunk.size == SpeechFeatures.CHUNK) { "A chunk is ${SpeechFeatures.CHUNK} samples" }
        val keep = raw.size - chunk.size
        System.arraycopy(raw, chunk.size, raw, 0, keep)
        for (i in chunk.indices) raw[keep + i] = chunk[i].toFloat()
        val frames = features.mel(raw)
        System.arraycopy(mel, frames.size, mel, 0, mel.size - frames.size)
        val start = mel.size - frames.size
        for (i in frames.indices) mel[start + i] = frames[i] / 10f + 2f
        return features.embed(mel)
    }
}

/** One sample of the user's "Hey Anu": its embeddings from the start of speech until just after it (ADR-121). */
class WakeTemplate(frames: List<FloatArray>) {
    /** Unit length, so a dot product is the cosine. */
    val frames: List<FloatArray> = frames.map(::unit)
}

/**
 * What Anu keeps of the user's "Hey Anu" (ADR-121): a few samples' embeddings and how close live audio must come to
 * one of them. It's derived from their voice, so it's stored encrypted and never leaves the phone.
 */
class WakeVoice(val templates: List<WakeTemplate>, val threshold: Float) {

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeFloat(threshold)
            out.writeInt(templates.size)
            for (template in templates) {
                out.writeInt(template.frames.size)
                for (frame in template.frames) {
                    out.writeInt(frame.size)
                    frame.forEach(out::writeFloat)
                }
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val VERSION = 1

        /** Null for bytes this version doesn't understand. */
        fun decode(bytes: ByteArray): WakeVoice? = runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != VERSION) return null
                val threshold = input.readFloat()
                val templates = List(input.readInt()) {
                    WakeTemplate(List(input.readInt()) { FloatArray(input.readInt()) { input.readFloat() } })
                }
                WakeVoice(templates, threshold)
            }
        }.getOrNull()
    }
}

/**
 * How far a template is from the end of live audio: dynamic time warping with an open start, so the phrase can begin
 * anywhere in [live] but must end at its last frame, at the mean cosine distance along the best path. 0 is the same
 * sound; openWakeWord's embeddings put different words of one voice at about 0.2 (ADR-121).
 */
object WakeDistance {
    fun of(template: List<FloatArray>, live: List<FloatArray>): Float {
        val rows = template.size
        val cols = live.size
        if (rows == 0 || cols == 0) return Float.MAX_VALUE
        // Cost so far and steps so far, a row at a time. Each step picks the predecessor with the lowest mean.
        var cost = FloatArray(cols)
        var steps = IntArray(cols)
        var nextCost = FloatArray(cols)
        var nextSteps = IntArray(cols)
        for (j in 0 until cols) {
            cost[j] = distance(template[0], live[j])
            steps[j] = 1
        }
        for (i in 1 until rows) {
            for (j in 0 until cols) {
                var best = cost[j]
                var n = steps[j]
                if (j > 0) {
                    if (cost[j - 1] / steps[j - 1] < best / n) {
                        best = cost[j - 1]
                        n = steps[j - 1]
                    }
                    if (nextCost[j - 1] / nextSteps[j - 1] < best / n) {
                        best = nextCost[j - 1]
                        n = nextSteps[j - 1]
                    }
                }
                nextCost[j] = best + distance(template[i], live[j])
                nextSteps[j] = n + 1
            }
            cost = nextCost.also { nextCost = cost }
            steps = nextSteps.also { nextSteps = steps }
        }
        return cost[cols - 1] / steps[cols - 1]
    }

    /** Both are unit length. */
    private fun distance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (k in a.indices) dot += a[k] * b[k]
        return 1f - dot
    }
}

/**
 * Matches live embeddings against the user's samples (ADR-121). [push] takes each new embedding and returns the best
 * distance to any sample, ending at it. Each sample is compared with up to twice its length of the latest audio, so a
 * slower "Hey Anu" still fits.
 */
class WakeMatcher(private val voice: WakeVoice) {
    private val longest = voice.templates.maxOfOrNull { it.frames.size } ?: 0
    private val recent = ArrayDeque<FloatArray>()

    fun push(embedding: FloatArray): Float {
        recent.addLast(unit(embedding))
        while (recent.size > longest * 2) recent.removeFirst()
        var best = Float.MAX_VALUE
        for (template in voice.templates) {
            val size = template.frames.size
            val window = minOf(size * 2, recent.size)
            if (window < (size + 1) / 2) continue
            best = minOf(best, WakeDistance.of(template.frames, recent.subList(recent.size - window, recent.size)))
        }
        return best
    }

    fun reset() = recent.clear()
}

/**
 * Teaching "Hey Anu" the user's voice (ADR-121). A recording becomes a [WakeTemplate] from where its speech is; the
 * samples' distances from each other set how close live audio must come.
 */
object WakeEnrollment {
    /** How many samples make a voice. */
    const val SAMPLES = 4

    /** A sample further than this on average from the others is asked for again. */
    const val TOO_DIFFERENT = 0.2f

    /**
     * Live audio must come closer to its nearest sample than this share of the furthest two samples are from each
     * other, within [MIN_THRESHOLD] and [MAX_THRESHOLD]. On spike S16's voices, a held-out "Hey Anu" was at 0.023 to
     * 0.048 and the closest look-alike ("Hey Andrew") at 0.076 (ADR-121).
     */
    const val MARGIN = 0.8f
    const val MIN_THRESHOLD = 0.055f
    const val MAX_THRESHOLD = 0.085f

    /** Quieter than about -40 dBFS at its loudest is too quiet to learn from. */
    private const val MIN_PEAK_RMS = 300.0
    private const val FRAME = 160
    private const val MIN_SPEECH_MS = 250
    private const val MAX_SPEECH_MS = 2_000

    sealed interface Sample {
        data class Learned(val template: WakeTemplate) : Sample

        data class Again(val reason: String) : Sample
    }

    /**
     * The sample in [audio], given the embeddings [WakeStream] made of it from its first sample, one per chunk: those
     * whose last chunk ends from 0.2 s into the speech until 0.16 s after it.
     */
    fun sample(audio: ShortArray, embeddings: List<FloatArray>): Sample {
        val span = speech(audio) ?: return Sample.Again("Didn't hear you. Say “Hey Anu” a little louder.")
        val millis = (span.last - span.first) * 1_000 / SpeechFeatures.RATE
        if (millis < MIN_SPEECH_MS) return Sample.Again("That was very short. Say “Hey Anu” again.")
        if (millis > MAX_SPEECH_MS) return Sample.Again("There’s other sound around you. Say just “Hey Anu”, close to the phone.")
        val frames = embeddings.filterIndexed { k, _ ->
            val end = (k + 1) * SpeechFeatures.CHUNK
            end >= span.first + 3_200 && end <= span.last + 2_560
        }
        if (frames.size < 2) return Sample.Again("That was very short. Say “Hey Anu” again.")
        return Sample.Learned(WakeTemplate(frames))
    }

    /** Whether [template] sounds like the samples already kept; the first always does. */
    fun fits(template: WakeTemplate, kept: List<WakeTemplate>): Boolean {
        if (kept.isEmpty()) return true
        val mean = kept.map { distance(template, it) }.average()
        return mean <= TOO_DIFFERENT
    }

    fun voice(templates: List<WakeTemplate>): WakeVoice {
        var furthest = 0f
        for (a in templates) for (b in templates) if (a !== b) furthest = maxOf(furthest, WakeDistance.of(a.frames, b.frames))
        return WakeVoice(templates, (furthest * MARGIN).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD))
    }

    /** The larger of the two directions, since an open start isn't symmetric. */
    private fun distance(a: WakeTemplate, b: WakeTemplate): Float =
        maxOf(WakeDistance.of(a.frames, b.frames), WakeDistance.of(b.frames, a.frames))

    /** The samples from the first to the last 10 ms frame louder than a tenth of the loudest, or null if nothing was loud enough. */
    internal fun speech(audio: ShortArray): IntRange? {
        val count = audio.size / FRAME
        if (count == 0) return null
        val rms = DoubleArray(count) { f ->
            var sum = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) sum += audio[i].toDouble() * audio[i]
            sqrt(sum / FRAME)
        }
        val peak = rms.max()
        // The room's own sound: the quietest fifth of the recording. Speech must stand 12 dB above it.
        val floor = rms.sorted()[count / 5]
        if (peak < maxOf(MIN_PEAK_RMS, floor * 4)) return null
        // The loudest burst, out to where it falls quiet for [GAP_FRAMES], so other voices before or after it are left out.
        val loud = maxOf(peak / 10, floor * 2)
        val top = rms.indices.maxBy { rms[it] }
        var first = top
        var quiet = 0
        for (f in top - 1 downTo 0) {
            if (rms[f] > loud) {
                first = f
                quiet = 0
            } else if (++quiet >= GAP_FRAMES) {
                break
            }
        }
        var last = top
        quiet = 0
        for (f in top + 1 until count) {
            if (rms[f] > loud) {
                last = f
                quiet = 0
            } else if (++quiet >= GAP_FRAMES) {
                break
            }
        }
        return first * FRAME..(last + 1) * FRAME
    }

    /** 300 ms: longer than the pause between "Hey" and "Anu". */
    private const val GAP_FRAMES = 30

    /**
     * Whether a sample's recording can end: speech was heard, then [quietChunks] chunks in a row were quiet, both
     * measured from the quietest chunk so far, so a noisy room still ends. [levels] are each chunk's loudness from 0 to
     * 1 ([level]), where 0.1 is 6 dB.
     */
    fun ended(levels: List<Float>, quietChunks: Int = 8): Boolean {
        val floor = levels.minOrNull() ?: return false
        val loud = levels.indexOfFirst { it >= maxOf(MIN_SPEECH_LEVEL, floor + 0.2f) }
        if (loud < 0 || levels.size - loud <= quietChunks) return false
        return levels.takeLast(quietChunks).all { it < floor + 0.1f }
    }

    /** About -39 dBFS on [level]'s scale. */
    private const val MIN_SPEECH_LEVEL = 0.35f
}

private fun unit(vector: FloatArray): FloatArray {
    var sum = 0f
    for (v in vector) sum += v * v
    val norm = sqrt(sum)
    return if (norm == 0f) vector.copyOf() else FloatArray(vector.size) { vector[it] / norm }
}
