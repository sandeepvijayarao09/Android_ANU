package io.github.sandeepvijayarao09.anu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Anu as the phone's digital assistant (ARCHITECTURE §13, ADR-044). Holding the power button, or the
 * corner swipe, opens Anu over whatever app is showing. "Hey Anu" uses [show] (ADR-045).
 */
class AnuVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = this
    }

    override fun onShutdown() {
        current = null
        super.onShutdown()
    }

    companion object {
        const val EXTRA_QUERY = "io.github.sandeepvijayarao09.anu.QUERY"

        @Volatile
        private var current: AnuVoiceInteractionService? = null

        /** True while Anu is the phone's assistant, so it can open itself over other apps. */
        val active: Boolean get() = current != null

        /** Opens the assistant overlay over whatever is on screen, with what followed "Hey Anu", if anything. */
        fun show(query: String?): Boolean {
            val service = current ?: return false
            service.showSession(Bundle().apply { query?.let { putString(EXTRA_QUERY, it) } }, 0)
            return true
        }
    }
}

class AnuSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AnuSession(this)
}

/**
 * The session has no window of its own: it opens [AssistantActivity] as the assistant. Screen content
 * the system may offer (assist data) is ignored; Anu never reads the screen in v1 (ADR-044).
 */
class AnuSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistantActivity::class.java)
        args?.getString(AnuVoiceInteractionService.EXTRA_QUERY)?.let { intent.putExtra(AnuVoiceInteractionService.EXTRA_QUERY, it) }
        startAssistantActivity(intent)
        hide()
    }
}

/**
 * The speech recognizer every assistant must provide. While Anu is the assistant it also serves other
 * apps' voice input, so it passes each request straight to the phone's on-device recognizer, and never
 * to a server (ADR-043).
 */
class AnuRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        recognizer?.destroy()
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            callback.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = callback.readyForSpeech(params ?: Bundle())
                override fun onBeginningOfSpeech() = callback.beginningOfSpeech()
                override fun onRmsChanged(rmsdB: Float) = callback.rmsChanged(rmsdB)
                override fun onBufferReceived(buffer: ByteArray?) {
                    buffer?.let(callback::bufferReceived)
                }
                override fun onEndOfSpeech() = callback.endOfSpeech()
                override fun onError(error: Int) = callback.error(error)
                override fun onResults(results: Bundle?) = callback.results(results ?: Bundle())
                override fun onPartialResults(partialResults: Bundle?) = callback.partialResults(partialResults ?: Bundle())
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(intent)
        }
    }

    override fun onStopListening(callback: Callback) {
        recognizer?.stopListening()
    }

    override fun onCancel(callback: Callback) {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
