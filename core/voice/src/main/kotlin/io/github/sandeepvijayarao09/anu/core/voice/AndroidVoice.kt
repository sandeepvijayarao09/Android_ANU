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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
class AndroidSpeechInput(
    private val context: Context,
    /** Debug builds: when each listen ended and why, never words (ADR-129). */
    private val log: (String) -> Unit = {},
) : SpeechInput {

    override val available: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        if (!available) {
            trySend(SpeechEvent.Error("This phone has no on-device speech recognition."))
            close()
            awaitClose {}
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        // Ends the listen once what was heard sounds finished, instead of always waiting the recognizer's 2 s (ADR-129).
        val endpointer = Endpointer(language = Locale.getDefault().language)
        var stopAsked = -1L
        val started = SystemClock.elapsedRealtime()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                log("Listen: ready after ${SystemClock.elapsedRealtime() - started} ms")
                trySend(SpeechEvent.Listening)
            }

            // Roughly -2 to 10 dB while someone speaks.
            override fun onRmsChanged(rmsdB: Float) {
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                endpointer.level(level, SystemClock.elapsedRealtime())
                trySend(SpeechEvent.Level(level))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let {
                    endpointer.partial(it, SystemClock.elapsedRealtime())
                    trySend(SpeechEvent.Partial(it))
                }
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                val now = SystemClock.elapsedRealtime()
                if (endpointer.spokeAt >= 0) {
                    VoiceLatency.heard(now)
                    val how = if (stopAsked >= 0) "Anu ended it after ${stopAsked - endpointer.spokeAt} ms of quiet" else "the recognizer ended it"
                    log("Listen: words final ${now - endpointer.spokeAt} ms after the user stopped; $how")
                }
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
        val ticker = launch {
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                if (stopAsked < 0 && endpointer.done(now)) {
                    stopAsked = now
                    recognizer.stopListening() // the words so far come back as the result
                }
            }
        }
        awaitClose {
            ticker.cancel()
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

    private companion object {
        const val TICK_MS = 50L
    }

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
 * When the user's last words were final, so the first word spoken back can say how long the user waited (ADR-129).
 * Debug logs only.
 */
object VoiceLatency {
    @Volatile private var heardAt = -1L

    fun heard(now: Long) {
        heardAt = now
    }

    /** Milliseconds since the last words were final, once, or null. */
    fun answered(now: Long): Long? {
        val at = heardAt
        heardAt = -1L
        return if (at < 0) null else now - at
    }
}

/**
 * Text to speech with the phone's own engine. Sentences queue up, and [speaking] follows the queue,
 * so the orb can show when Anu is talking.
 * - It speaks with the clearest voice on the phone for the user's language, never one that needs the network (I3,
 *   ADR-129), as the assistant (`USAGE_ASSISTANT`), and asks other audio to duck while it talks, as Google's assistant
 *   does.
 */
class AndroidSpeechOutput(
    context: Context,
    /** Debug builds: the voice chosen and how long the first word took, never words (ADR-129). */
    private val log: (String) -> Unit = {},
) : SpeechOutput {
    private val state = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = state.asStateFlow()

    private val pending = AtomicInteger(0)
    private val ids = AtomicInteger(0)

    @Volatile
    private var ready = false
    private val queuedBeforeReady = mutableListOf<String>()

    @Volatile private var askedAt = -1L

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            chooseVoice()
            synchronized(queuedBeforeReady) {
                queuedBeforeReady.forEach(::enqueue)
                queuedBeforeReady.clear()
            }
        }
    }.apply {
        setAudioAttributes(attributes)
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                state.value = true
                val now = SystemClock.elapsedRealtime()
                val asked = askedAt
                if (asked >= 0) {
                    askedAt = -1L
                    val waited = VoiceLatency.answered(now)
                    log("Speech: first audio ${now - asked} ms after its sentence" + (waited?.let { ", $it ms after the user's words were final" } ?: ""))
                }
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
        quiet()
    }

    fun shutdown() = tts.shutdown()

    private fun enqueue(sentence: String) {
        if (pending.getAndIncrement() == 0) {
            askedAt = SystemClock.elapsedRealtime()
            audio.requestAudioFocus(focus)
        }
        state.value = true
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "anu-${ids.incrementAndGet()}")
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            quiet()
        }
    }

    private fun quiet() {
        state.value = false
        askedAt = -1L
        audio.abandonAudioFocusRequest(focus)
    }

    /** The clearest voice on the phone for the user's language; the engine's default when none fits. */
    private fun chooseVoice() {
        val locale = Locale.getDefault()
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        val candidates = voices.map { voice ->
            TtsVoice(
                name = voice.name,
                // Engines name locales differently ("en_US", "eng_USA"), so both sides are compared as ISO 639-2 and 3166 alpha-3.
                language = iso3Language(voice.locale),
                country = iso3Country(voice.locale),
                quality = voice.quality,
                latency = voice.latency,
                needsNetwork = voice.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty(),
            )
        }
        val best = VoicePick.best(candidates, iso3Language(locale), iso3Country(locale))
        val voice = best?.let { voices.firstOrNull { v -> v.name == it.name } }
        if (best == null || voice == null) {
            // No offline voice for the language: the engine's own for it, if it has one, else its default.
            val result = tts.setLanguage(locale)
            log("Speech: no offline voice for ${locale.toLanguageTag()} among ${voices.size}; engine language result $result")
            return
        }
        tts.voice = voice
        log("Speech: voice ${best.name}, quality ${best.quality}, latency ${best.latency}, of ${voices.size}")
    }

    private fun iso3Language(locale: Locale): String = runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: locale.language

    private fun iso3Country(locale: Locale): String = runCatching { locale.isO3Country }.getOrNull()?.takeIf { it.isNotEmpty() } ?: locale.country
}
