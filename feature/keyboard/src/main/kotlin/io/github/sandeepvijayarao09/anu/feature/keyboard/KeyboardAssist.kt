package io.github.sandeepvijayarao09.anu.feature.keyboard

import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.agent.CascadeEvent
import io.github.sandeepvijayarao09.anu.core.agent.Tokens
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.security.PiiDetector
import io.github.sandeepvijayarao09.anu.feature.writing.WritingOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AssistState {
    data object Idle : AssistState

    /** The model is writing; [partial] is what it has streamed so far. */
    data class Working(val action: KeyboardAction, val partial: String = "", val tier: Tier? = null) : AssistState

    /** A preview. Nothing reaches the field until the user taps [KeyboardAssist.accept]. */
    data class Ready(
        val action: KeyboardAction,
        val result: String,
        val plan: EditPlan,
        val tier: Tier,
        /** Whom the reply is to, from the notification Anu saw. */
        val chatTitle: String? = null,
    ) : AssistState {
        val replaces: Boolean get() = plan.mode != EditMode.INSERT && !plan.keep
    }

    /** Why nothing came of it, in a sentence. */
    data class Note(val text: String) : AssistState
}

/**
 * The AI strip's work (ADR-109): one task at a time on the field's text, through AI Writing's cascade with no tools.
 * - **On the phone only.** The cloud is taken out of the device state, and the cascade the app passes has no cloud
 *   brain, so a request never leaves the phone and never asks to.
 * - **Nothing is committed here.** A result becomes a [AssistState.Ready] preview; the keyboard commits it only on
 *   the user's tap, and only when [KeyboardText.stillThere].
 * - **Nothing is kept.** The text lives in this state until it's inserted, discarded or the field changes. It isn't
 *   logged, saved, or learned from (memory never learns from typed text).
 */
class KeyboardAssist(
    private val cascade: Cascade,
    private val device: () -> DeviceState,
    private val scope: CoroutineScope,
    private val memory: suspend () -> String? = { null },
) {
    private val mutable = MutableStateFlow<AssistState>(AssistState.Idle)
    val state: StateFlow<AssistState> = mutable.asStateFlow()

    private var running: Job? = null

    fun run(action: KeyboardAction, field: FieldText, chat: ChatContext? = null) {
        running?.cancel()
        val plan = when (action) {
            KeyboardAction.CONTINUE -> KeyboardText.forContinue(field) ?: return note("Type a few words first, and Anu writes what comes next.")
            KeyboardAction.REPLY -> KeyboardText.forReply(field)
            else -> KeyboardText.forRewrite(field) ?: return note("Type or select some text first.")
        }
        if (action == KeyboardAction.REPLY && chat == null && plan.input.isBlank()) {
            return note("No message to reply to. Anu sees the messages this app shows in its notifications.")
        }
        mutable.value = AssistState.Working(action)
        running = scope.launch {
            try {
                write(action, plan, chat)
            } catch (e: CancellationException) {
                if (mutable.value is AssistState.Working) mutable.value = AssistState.Idle
                throw e
            }
        }
    }

    private suspend fun write(action: KeyboardAction, plan: EditPlan, chat: ChatContext?) {
        val writing = action.writing
        val block = if (writing?.usesMemory != false) memoryOrNull() else null
        val request = when {
            writing != null -> KeyboardPrompt.rewrite(writing, plan.input, block)
            action == KeyboardAction.CONTINUE -> KeyboardPrompt.continuation(plan.input, block)
            else -> KeyboardPrompt.reply(chat, plan.input, block)
        }
        val seen = plan.input + chat?.lines.orEmpty().joinToString("\n") { it.text }
        val route = RouteRequest(
            text = action.label,
            skill = Skill.WRITING,
            // Typed text can hold anything personal, so it is at least S1 (§12.1).
            dataClasses = setOf(DataClass.PERSONAL) + PiiDetector.dataClasses(seen),
            promptTokens = Tokens.ofRequest(request),
        )
        // The cloud is never a choice here (ADR-109).
        val onPhone = device().let { it.copy(availableTiers = it.availableTiers - Tier.CLOUD) }
        cascade.run(route, onPhone, request, WritingOutput.validator).collect { event ->
            val working = mutable.value as? AssistState.Working ?: return@collect
            when (event) {
                is CascadeEvent.Routed -> mutable.value = working.copy(tier = event.decision.tier)
                is CascadeEvent.TextDelta -> mutable.value = working.copy(partial = working.partial + event.text)
                is CascadeEvent.Discarded -> mutable.value = working.copy(partial = "")
                is CascadeEvent.Escalated -> mutable.value = working.copy(partial = "", tier = event.to)
                is CascadeEvent.ConsentNeeded -> note("This needs more than the models on this phone, and the keyboard keeps everything on it.")
                is CascadeEvent.Completed -> {
                    val cleaned = WritingOutput.clean(event.text)
                    val result = if (action == KeyboardAction.CONTINUE) KeyboardText.withoutEcho(plan.input, cleaned) else cleaned
                    mutable.value = if (result.isBlank()) {
                        AssistState.Note("Anu didn't come up with anything. Try again.")
                    } else {
                        AssistState.Ready(action, result, plan, event.tier, chat?.title.takeIf { action == KeyboardAction.REPLY })
                    }
                }
                is CascadeEvent.Failed -> note(
                    if (event.reasons.any { it == "no model tier is available" }) {
                        "No model is on this phone yet. Download one in Anu's Settings."
                    } else {
                        "Anu couldn't do that on this phone."
                    },
                )
            }
        }
    }

    /** The text to commit for the preview, or null when the field changed since (then the preview stays for Discard). */
    fun accept(now: FieldText): Pair<EditPlan, String>? {
        val ready = mutable.value as? AssistState.Ready ?: return null
        if (!KeyboardText.stillThere(ready.plan, now)) {
            mutable.value = AssistState.Note("Your text changed, so Anu left it as it is. Tap ${ready.action.label} again.")
            return null
        }
        mutable.value = AssistState.Idle
        return ready.plan to KeyboardText.output(ready.plan, ready.result)
    }

    fun stop() {
        running?.cancel()
        running = null
        mutable.value = AssistState.Idle
    }

    /** Discard, a new field, or the keyboard hiding: the text is dropped. */
    fun clear() = stop()

    private fun note(text: String) {
        mutable.value = AssistState.Note(text)
    }

    private suspend fun memoryOrNull(): String? = try {
        memory()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
