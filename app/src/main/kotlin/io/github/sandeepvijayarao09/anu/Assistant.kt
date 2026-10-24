package io.github.sandeepvijayarao09.anu

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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

        /** The Quick Settings tile asked about the screen: the card's next question uses it (ADR-108). */
        const val EXTRA_USE_SCREEN = "io.github.sandeepvijayarao09.anu.USE_SCREEN"

        @Volatile
        private var current: AnuVoiceInteractionService? = null

        /** True while Anu is the phone's assistant, so it can open itself over other apps. */
        val active: Boolean get() = current != null

        /**
         * Opens the assistant overlay over whatever is on screen, with what followed "Hey Anu", if anything. It asks
         * Android for the screen as the power button does, so "Hey Anu, what's this?" can use it (EXP-2, ADR-083);
         * the screen is still read only because the question asks about it, or because the tile did ([useScreen]).
         */
        fun show(query: String?, useScreen: Boolean = false): Boolean {
            val service = current ?: return false
            service.showSession(
                Bundle().apply {
                    query?.let { putString(EXTRA_QUERY, it) }
                    if (useScreen) putBoolean(EXTRA_USE_SCREEN, true)
                },
                VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
            )
            return true
        }
    }
}

class AnuSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AnuSession(this)
}

/**
 * The session has no window of its own: it opens [AssistantActivity] as the assistant. What Android offers of the
 * screen below, its text structure and screenshot, goes to [ScreenHandoff] in memory, for the card to use only if the
 * user asks about the screen (EXP-2, ADR-083). The session stays shown until both arrive, or [HANDOFF_WAIT_MS] pass,
 * because Android offers them only to a session that's showing (A5); debug builds log when each part arrives and its
 * size, never its content.
 */
class AnuSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }
    private var shownAt = 0L
    private var assisted = false
    private var screenshotted = false

    override fun onCreate() {
        super.onCreate()
        // The card is the activity; the session needs no window, so nothing of it can cover the card.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shownAt = SystemClock.elapsedRealtime()
        assisted = false
        screenshotted = false
        ScreenHandoff.begin(System.currentTimeMillis())
        val intent = Intent(context, AssistantActivity::class.java)
        args?.getString(AnuVoiceInteractionService.EXTRA_QUERY)?.let { intent.putExtra(AnuVoiceInteractionService.EXTRA_QUERY, it) }
        if (args?.getBoolean(AnuVoiceInteractionService.EXTRA_USE_SCREEN) == true) intent.putExtra(AnuVoiceInteractionService.EXTRA_USE_SCREEN, true)
        startAssistantActivity(intent)
        if (BuildConfig.DEBUG) {
            Log.d("Anu", "Assistant: shown, text ${showFlags and SHOW_WITH_ASSIST != 0}, screenshot ${showFlags and SHOW_WITH_SCREENSHOT != 0}")
        }
        handler.postDelayed(hideLater, HANDOFF_WAIT_MS)
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        ScreenHandoff.structure(state.assistStructure)
        if (BuildConfig.DEBUG) {
            val structure = state.assistStructure
            val windows = structure?.windowNodeCount ?: 0
            val texts = (0 until windows).sumOf { count(structure!!.getWindowNodeAt(it).rootViewNode) }
            Log.d("Anu", "Assistant: screen text after ${SystemClock.elapsedRealtime() - shownAt} ms, $windows windows, $texts text nodes, focused ${state.isFocused}")
        }
        assisted = true
        hideWhenHandedOver()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        ScreenHandoff.screenshot(screenshot)
        if (BuildConfig.DEBUG) {
            val size = screenshot?.let { "${it.width}x${it.height}" } ?: "none"
            Log.d("Anu", "Assistant: screenshot after ${SystemClock.elapsedRealtime() - shownAt} ms, $size")
        }
        screenshotted = true
        hideWhenHandedOver()
    }

    override fun onHide() {
        handler.removeCallbacks(hideLater)
        super.onHide()
    }

    private fun hideWhenHandedOver() {
        if (assisted && screenshotted) {
            handler.removeCallbacks(hideLater)
            hide()
        }
    }

    /** How many nodes under [node] show text. Counts only. */
    private fun count(node: AssistStructure.ViewNode): Int =
        (if (!node.text.isNullOrEmpty()) 1 else 0) + (0 until node.childCount).sumOf { count(node.getChildAt(it)) }

    private companion object {
        /** How long the session waits for the screen before it hides anyway. */
        const val HANDOFF_WAIT_MS = 2_000L
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
