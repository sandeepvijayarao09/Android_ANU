package io.github.sandeepvijayarao09.anu.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * 16-bit PCM WAV files, the one format Anu hands the model (ADR-078). LiteRT-LM's decoder takes WAV at any rate and
 * channel count but not AAC or Opus, so recordings are recorded or decoded to mono 16-bit PCM and wrapped here.
 */
object Wav {
    /** The sample rate Anu records at, the rate speech models expect. */
    const val SPEECH_RATE = 16_000

    private const val HEADER = 44

    /** Mono 16-bit little-endian [pcm] at [rate], as a WAV file. */
    fun of(pcm: ByteArray, rate: Int = SPEECH_RATE): ByteArray {
        val out = ByteBuffer.allocate(HEADER + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(ascii("RIFF")).putInt(36 + pcm.size).put(ascii("WAVE"))
        out.put(ascii("fmt ")).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        out.put(ascii("data")).putInt(pcm.size).put(pcm)
        return out.array()
    }

    /** A WAV file's mono 16-bit samples and rate, or null when it isn't one Anu wrote ([of]). */
    fun read(wav: ByteArray): Pcm? {
        if (wav.size < HEADER || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var rate = 0
        var mono16 = false
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = buffer.getInt(at + 4)
            if (size < 0 || at + 8 + size > wav.size) return null
            when (id) {
                "fmt " -> {
                    mono16 = buffer.getShort(at + 8).toInt() == 1 && buffer.getShort(at + 10).toInt() == 1 && buffer.getShort(at + 22).toInt() == 16
                    rate = buffer.getInt(at + 12)
                }
                "data" -> return if (mono16 && rate > 0) Pcm(wav.copyOfRange(at + 8, at + 8 + size), rate) else null
            }
            at += 8 + size + (size and 1)
        }
        return null
    }

    /** How many seconds [pcmBytes] of mono 16-bit audio at [rate] last, to the nearest second, and at least 1 once there is sound. */
    fun seconds(pcmBytes: Int, rate: Int): Int = if (pcmBytes <= 0) 0 else max(1, (pcmBytes / 2 + rate / 2) / rate)

    /**
     * [wav] cut into WAV files of at most [maxSeconds] each, so each fits the model's window (ADR-078). Each cut falls
     * on the quietest moment of the last [searchSeconds] before the limit, between words where there is a pause.
     */
    fun split(wav: ByteArray, maxSeconds: Int, searchSeconds: Int = 5): List<ByteArray> {
        val pcm = read(wav) ?: return listOf(wav)
        val samples = pcm.bytes.size / 2
        val limit = maxSeconds * pcm.rate
        if (samples <= limit) return listOf(wav)
        val shorts = ByteBuffer.wrap(pcm.bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val window = pcm.rate / 10
        val parts = mutableListOf<ByteArray>()
        var start = 0
        while (samples - start > limit) {
            val end = start + limit
            var cut = end
            var quietest = Long.MAX_VALUE
            var at = max(start + window, end - searchSeconds * pcm.rate)
            while (at + window <= end) {
                var energy = 0L
                for (i in at until at + window) energy += shorts.get(i).toLong().let { it * it }
                if (energy < quietest) {
                    quietest = energy
                    cut = at + window / 2
                }
                at += window / 2
            }
            parts += of(pcm.bytes.copyOfRange(start * 2, cut * 2), pcm.rate)
            start = cut
        }
        parts += of(pcm.bytes.copyOfRange(start * 2, samples * 2), pcm.rate)
        return parts
    }

    private fun ascii(tag: String) = tag.toByteArray(Charsets.US_ASCII)

    /** Mono 16-bit little-endian samples at [rate]. */
    class Pcm(val bytes: ByteArray, val rate: Int)
}
