package io.github.sandeepvijayarao09.anu.core.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speech recognition with the phone's on-device recognizer only (ADR-043). Another recognizer might
 * send audio to a server outside the Privacy Ledger (I3), so when there is no on-device one, voice is
 * simply unavailable.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    override val available: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        if (!available) {
            trySend(SpeechEvent.Error("This phone has no on-device speech recognition."))
            close()
            awaitClose {}
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Listening)
            }

            // Roughly -2 to 10 dB while someone speaks.
            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                trySend(if (text.isNullOrBlank()) SpeechEvent.Error("Didn't catch that.", heardNothing = true) else SpeechEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(
                    SpeechEvent.Error(
                        describe(error),
                        heardNothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        needsLanguagePack = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    ),
                )
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(recognition())
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main) // SpeechRecognizer must be used on the main thread

    /** Starts the system's download of the on-device model for the phone's language (API 33+). */
    override fun downloadLanguagePack() {
        if (Build.VERSION.SDK_INT < 33 || !available) return
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.triggerModelDownload(recognition())
        recognizer.destroy()
    }

    private fun recognition() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        // A pause to think shouldn't end the sentence: on the Pixel a short one sent half of it (ADR-101). The
        // recognizer may treat these as hints.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)

    private fun first(bundle: Bundle?): String? = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't working."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Anu needs the microphone permission."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "On-device speech for your language isn't on this phone yet."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition is busy. Try again in a moment."
        else -> "Speech recognition stopped (code $error)."
    }
}

/**
 * Text to speech with the phone's own engine. Sentences queue up, and [speaking] follows the queue,
 * so the orb can show when Anu is talking.
 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val state = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = state.asStateFlow()

    private val pending = AtomicInteger(0)
    private val ids = AtomicInteger(0)

    @Volatile
    private var ready = false
    private val queuedBeforeReady = mutableListOf<String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            synchronized(queuedBeforeReady) {
                queuedBeforeReady.forEach(::enqueue)
                queuedBeforeReady.clear()
            }
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                state.value = true
            }

            override fun onDone(utteranceId: String?) = finished()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
    }

    override fun speak(sentence: String) {
        if (sentence.isBlank()) return
        if (!ready) {
            synchronized(queuedBeforeReady) { queuedBeforeReady += sentence }
            return
        }
        enqueue(sentence)
    }

    override fun stop() {
        synchronized(queuedBeforeReady) { queuedBeforeReady.clear() }
        pending.set(0)
        tts.stop()
        state.value = false
    }

    fun shutdown() = tts.shutdown()

    private fun enqueue(sentence: String) {
        pending.incrementAndGet()
        state.value = true
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "anu-${ids.incrementAndGet()}")
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            state.value = false
        }
    }
}
