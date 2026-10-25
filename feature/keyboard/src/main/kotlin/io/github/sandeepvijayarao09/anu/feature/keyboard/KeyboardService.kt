package io.github.sandeepvijayarao09.anu.feature.keyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the app gives the keyboard (ADR-109): AI Writing's cascade with no cloud brain in it, the phone's state, the
 * memory block for the user's voice, and the newest chat Anu saw from an app's notifications, for Reply.
 * [debug] gets one line per field and per task in debug builds: kinds and timings, never text.
 */
class KeyboardHost(
    val cascade: Cascade,
    val device: () -> DeviceState,
    val memory: suspend () -> String? = { null },
    val chat: (app: String) -> ChatContext? = { null },
    val debug: ((String) -> Unit)? = null,
    /** The phone's own writing model, such as Gemini Nano, for the rewrites (ADR-116). */
    val system: io.github.sandeepvijayarao09.anu.core.model.SystemWriter? = null,
)

/**
 * Anu's keyboard (ARCHITECTURE §13, ADR-109). The app subclasses it to give [host].
 *
 * Privacy, in code rather than in a prompt:
 * - Keys go straight to the field with `commitText`: no composing text, no dictionary, no history, nothing learned.
 * - The field's text is read only when the user taps an AI action, and never in a private field ([FieldPolicy.private]).
 * - The AI works on this phone only, and a result reaches the field only on the user's tap.
 * - Nothing typed is logged, saved or sent.
 */
abstract class KeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    protected abstract fun host(): KeyboardHost

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = MainScope()
    private lateinit var host: KeyboardHost
    private lateinit var assist: KeyboardAssist

    private var policy by mutableStateOf(FieldPolicy.of(EditorInfo.TYPE_NULL, 0))
    private var shift by mutableStateOf(Shift.OFF)
    private var page by mutableStateOf(KeyPage.LETTERS)
    private var lastShiftTap = 0L
    /** The Ask field's text while it's open (ADR-122): Anu's own, never the app's, and dropped when it runs or closes. */
    private var asking by mutableStateOf<String?>(null)
    private var app = ""

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        host = host()
        assist = KeyboardAssist(host.cascade, host.device, scope, host.memory, host.system)
    }

    override fun onCreateInputView(): View {
        // Compose finds its lifecycle through the window's views; the keyboard's window is the service's own.
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@KeyboardService)
            setViewTreeSavedStateRegistryOwner(this@KeyboardService)
            setContent {
                AnuTheme {
                    val state by assist.state.collectAsState()
                    AnuKeyboard(policy, state, shift, page, ::tapShift, { page = it }, keys, ai, asking)
                }
            }
        }
    }

    // The keyboard never covers the app with the full-screen editor, in landscape too.
    override fun onEvaluateFullscreenMode(): Boolean = false

    // It shows with a hardware keyboard too, as the emulator has: the AI strip is still worth having there.
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        app = info.packageName.orEmpty()
        val knownChat = app.isNotEmpty() && host.chat(app) != null
        policy = FieldPolicy.of(info.inputType, info.imeOptions, knownChat)
        if (!restarting) {
            assist.clear()
            asking = null
            page = KeyPage.LETTERS
            shift = Shift.OFF
        }
        autoShift()
        host.debug?.invoke("Keyboard: ${policy.layout.name.lowercase()} field, private ${policy.private}, AI ${policy.ai}, chat ${policy.chat}")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        assist.clear()
        asking = null
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        autoShift()
    }

    override fun onDestroy() {
        scope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    /** Capitals at the start of a sentence, from the field's own caps mode; never asked of a private field. */
    private fun autoShift() {
        if (!policy.autoCaps) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        shift = ShiftRules.auto(shift, caps != 0)
    }

    private fun tapShift() {
        val now = SystemClock.uptimeMillis()
        shift = ShiftRules.tap(shift, now - lastShiftTap)
        lastShiftTap = now
    }

    private val keys = object : KeyboardInput {
        // While the Ask field is open, the keys type there, not into the app.
        override fun text(text: String) {
            val open = asking
            if (open != null) asking = KeyboardText.askEdit(open, text) else currentInputConnection?.commitText(text, 1)
            shift = ShiftRules.afterText(shift)
        }

        override fun backspace() {
            val open = asking
            if (open != null) asking = KeyboardText.askEdit(open, null) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }

        override fun enter() {
            if (asking != null) return ai.ask()
            val enter = policy.enter
            if (enter == EnterKey.NEWLINE) sendKeyChar('\n') else currentInputConnection?.performEditorAction(enter.actionId)
        }

        override fun nextKeyboard() {
            if (!switchToNextInputMethod(false)) pickKeyboard()
        }

        override fun pickKeyboard() = getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private val ai = object : AssistInput {
        override fun run(action: KeyboardAction) {
            if (!policy.ai) return
            if (action == KeyboardAction.ASK) {
                assist.clear()
                asking = ""
                shift = Shift.ONCE
                return
            }
            start(action, null)
        }

        override fun ask() {
            val instruction = asking ?: return
            if (instruction.isBlank()) return
            asking = null
            start(KeyboardAction.ASK, instruction)
        }

        private fun start(action: KeyboardAction, instruction: String?) {
            val field = readField() ?: return
            val started = SystemClock.uptimeMillis()
            assist.run(action, field, if (action == KeyboardAction.REPLY) host.chat(app) else null, instruction)
            host.debug?.let { log ->
                scope.launch {
                    val state = assist.state.first { it !is AssistState.Working }
                    log("Keyboard: ${action.name.lowercase()} ${outcome(state)} in ${SystemClock.uptimeMillis() - started} ms")
                }
            }
        }

        override fun accept(choice: String?) {
            val (plan, output) = assist.accept(readField() ?: return, choice) ?: return
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            if (plan.mode == EditMode.REPLACE_BEFORE) ic.deleteSurroundingText(plan.source.length, 0)
            ic.commitText(output, 1)
            ic.endBatchEdit()
        }

        override fun discard() {
            asking = null
            assist.clear()
        }
    }

    /** The text around the cursor, read once for one tap. Never from a private field. */
    private fun readField(): FieldText? {
        if (policy.private || !policy.ai) return null
        val ic = currentInputConnection ?: return null
        return FieldText(
            before = ic.getTextBeforeCursor(KeyboardText.READ_CHARS, 0)?.toString().orEmpty(),
            selected = ic.getSelectedText(0)?.toString().orEmpty(),
        )
    }
}

private fun outcome(state: AssistState): String = when (state) {
    is AssistState.Ready -> "ready on ${state.engine ?: state.tier?.name?.lowercase()}, ${state.result.length} chars"
    is AssistState.Note -> "ended with a note"
    else -> "stopped"
}
