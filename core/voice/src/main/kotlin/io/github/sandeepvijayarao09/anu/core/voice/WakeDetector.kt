package io.github.sandeepvijayarao09.anu.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.log10
import kotlin.math.sqrt

/** The microphone as 16 kHz mono 16-bit audio (ADR-121). */
interface MicInput {
    /** Cold: collecting opens the microphone, and cancelling closes it. Throws [MicUnavailable] when it can't open. */
    fun chunks(size: Int): Flow<ShortArray>
}

class MicUnavailable(reason: String) : Exception(reason)

/** "Hey Anu"'s two ways of listening, so the service can use either (ADR-045, ADR-121). */
fun interface WakeLoop {
    /** `onWake` gets the request after the wake phrase, if one was heard, and says whether Anu opened. */
    suspend fun run(onWake: suspend (request: String?) -> Boolean): WakeListener.Stop
}

/**
 * "Hey Anu" from the user's own samples (ADR-121). The microphone stays open, every 80 ms of audio becomes an
 * embedding on LiteRT, and [WakeMatcher] compares the latest with the samples. Audio is held only for the mel model's
 * 110 ms and the embeddings for under 3 s: nothing is kept, logged or sent.
 * - A match fires at its closest point, at most [settleChunks] chunks after the distance first dips under the
 *   threshold, so the whole phrase is in.
 * - Then `onWake` opens Anu with no request, since the recognizer didn't hear one: the card listens for it.
 * - A microphone that won't open is tried again after a pause, doubling each time; after [maxFailures] in a row it
 *   stops.
 *
 * [log] gets the distance of each wake and near miss, never audio, for debug builds. The models load and run in
 * [work], off the caller's thread.
 */
class WakeDetector(
    private val mic: MicInput,
    private val features: () -> SpeechFeatures,
    private val voice: WakeVoice,
    private val log: (String) -> Unit = {},
    private val maxFailures: Int = 5,
    private val backoffMillis: Long = 2_000,
    private val settleChunks: Int = 3,
    private val work: CoroutineContext = Dispatchers.Default,
) : WakeLoop {

    override suspend fun run(onWake: suspend (request: String?) -> Boolean): WakeListener.Stop {
        val model = withContext(work) { features() }
        try {
            val stream = WakeStream(model)
            val matcher = WakeMatcher(voice)
            var failures = 0
            while (true) {
                val woke = try {
                    listen(stream, matcher)
                } catch (e: MicUnavailable) {
                    failures++
                    if (failures >= maxFailures) return WakeListener.Stop.Failed(e.message ?: "The microphone is in use by another app.")
                    delay(backoffMillis shl (failures - 1))
                    continue
                }
                failures = 0
                if (woke) {
                    matcher.reset()
                    if (!onWake(null)) return WakeListener.Stop.CantOpen
                }
            }
        } finally {
            model.close()
        }
    }

    /** Listens until a match, which returns true, or the microphone's audio ends, which returns false. */
    private suspend fun listen(stream: WakeStream, matcher: WakeMatcher): Boolean {
        var best = Float.MAX_VALUE
        var since = -1
        var nearest = Float.MAX_VALUE
        var woke = false
        try {
            // The models finish their chunk before collecting returns, so they're never closed mid-run.
            mic.chunks(SpeechFeatures.CHUNK).map { chunk -> matcher.push(stream.push(chunk)) }.flowOn(work).collect { distance ->
                when {
                    since >= 0 -> {
                        since++
                        if (distance < best) best = distance
                        if (distance >= best || since >= settleChunks) {
                            woke = true
                            throw Matched
                        }
                    }
                    distance < voice.threshold -> {
                        best = distance
                        since = 0
                    }
                    distance < voice.threshold * NEAR -> nearest = minOf(nearest, distance)
                    nearest < Float.MAX_VALUE -> {
                        log("Hey Anu: near miss at ${format(nearest)}, threshold ${format(voice.threshold)}")
                        nearest = Float.MAX_VALUE
                    }
                }
            }
        } catch (_: MatchedSignal) {
            // Stops collecting, which closes the microphone before Anu opens.
        }
        if (woke) log("Hey Anu: woke at ${format(best)}, threshold ${format(voice.threshold)}")
        return woke
    }

    private open class MatchedSignal : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private object Matched : MatchedSignal()

    private companion object {
        /** A distance under this many thresholds is logged as a near miss. */
        const val NEAR = 1.5f

        fun format(value: Float) = "%.3f".format(value)
    }
}

/** How loud a chunk of 16-bit audio is, from 0 (-60 dBFS or quieter) to 1 (full scale), as [level] measures bytes. */
fun level(samples: ShortArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) sum += s.toDouble() * s
    val rms = sqrt(sum / samples.size)
    if (rms < 1) return 0f
    return ((20 * log10(rms / 32_768) + 60) / 60).toFloat().coerceIn(0f, 1f)
}
