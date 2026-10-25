package io.github.sandeepvijayarao09.anu.core.voice

import kotlinx.coroutines.delay

/**
 * The "Hey Anu" loop on the recognizer (ADR-045), for when the user hasn't taught Anu their voice (ADR-121). It
 * listens round after round and calls `onWake` with what followed the wake phrase. Everything else it hears is
 * dropped at once: never kept, logged or sent.
 * - Silence just starts another round.
 * - Other errors back off, doubling each time. After [maxFailures] in a row it stops.
 * - A missing language pack or recognizer stops it at once: waiting won't fix that.
 * - A recognizer that ends every round at once gets a pause, so the loop never spins.
 *
 * It runs until cancelled, or returns why it stopped for good.
 */
class WakeListener(
    private val speech: SpeechInput,
    /** Monotonic milliseconds, such as `SystemClock.elapsedRealtime`. */
    private val clock: () -> Long,
    private val maxFailures: Int = 5,
    private val backoffMillis: Long = 2_000,
    private val quickRoundMillis: Long = 1_000,
    private val maxQuickRounds: Int = 3,
    private val pauseMillis: Long = 5_000,
    private val roundGapMillis: Long = 250,
) : WakeLoop {
    sealed interface Stop {
        /** `onWake` couldn't open Anu, for example because it is no longer the assistant. */
        data object CantOpen : Stop

        data class Failed(val reason: String) : Stop
    }

    /** `onWake` gets the request after the wake phrase, or null for just "Hey Anu", and says whether Anu opened. */
    override suspend fun run(onWake: suspend (request: String?) -> Boolean): Stop {
        var failures = 0
        var quickRounds = 0
        while (true) {
            val startedAt = clock()
            var heard: String? = null
            var error: SpeechEvent.Error? = null
            speech.listen().collect { event ->
                when (event) {
                    is SpeechEvent.Final -> heard = event.text
                    is SpeechEvent.Error -> error = event
                    else -> Unit
                }
            }
            val text = heard
            val failure = error
            when {
                text != null -> {
                    failures = 0
                    val request = WakePhrase.remainder(text)
                    if (request != null && !onWake(request.ifBlank { null })) return Stop.CantOpen
                }
                failure == null || failure.heardNothing -> failures = 0
                failure.needsLanguagePack || !speech.available -> return Stop.Failed(failure.reason)
                else -> {
                    failures++
                    if (failures >= maxFailures) return Stop.Failed(failure.reason)
                    delay(backoffMillis shl (failures - 1))
                }
            }
            if (clock() - startedAt < quickRoundMillis) {
                if (++quickRounds >= maxQuickRounds) {
                    delay(pauseMillis)
                    quickRounds = 0
                }
            } else {
                quickRounds = 0
            }
            delay(roundGapMillis)
        }
    }
}
