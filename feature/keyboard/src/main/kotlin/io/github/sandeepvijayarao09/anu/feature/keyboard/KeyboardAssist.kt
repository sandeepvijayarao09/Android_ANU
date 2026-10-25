package io.github.sandeepvijayarao09.anu.feature.keyboard

import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.agent.CascadeEvent
import io.github.sandeepvijayarao09.anu.core.agent.Tokens
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.SystemWriter
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
        val tier: Tier?,
        /** Whom the reply is to, from the notification Anu saw. */
        val chatTitle: String? = null,
        /** The phone's own writing model that wrote it, such as "Gemini Nano", when no Gemma tier did (ADR-116). */
        val engine: String? = null,
        /** Emoji to choose from; the user taps one to insert it (ADR-122). */
        val choices: List<String> = emptyList(),
        /** What the user asked in the strip, for Ask. */
        val asked: String? = null,
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
    /** The phone's own writing model, tried first for the rewrites (ADR-116). */
    private val system: SystemWriter? = null,
) {
    private val mutable = MutableStateFlow<AssistState>(AssistState.Idle)
    val state: StateFlow<AssistState> = mutable.asStateFlow()

    private var running: Job? = null

    /** [ask] is the instruction typed into the strip, for [KeyboardAction.ASK]. */
    fun run(action: KeyboardAction, field: FieldText, chat: ChatContext? = null, ask: String? = null) {
        running?.cancel()
        val instruction = ask?.trim().orEmpty()
        if (action == KeyboardAction.ASK && instruction.isEmpty()) return note("Type what you'd like Anu to write or change.")
        val plan = when (action) {
            KeyboardAction.CONTINUE -> KeyboardText.forContinue(field) ?: return note("Type a few words first, and Anu writes what comes next.")
            KeyboardAction.EMOJI -> KeyboardText.forEmoji(field) ?: return note("Type a few words first, and Anu suggests emoji for them.")
            KeyboardAction.REPLY -> KeyboardText.forReply(field)
            KeyboardAction.ASK -> KeyboardText.forAsk(field)
            else -> KeyboardText.forRewrite(field) ?: return note("Type or select some text first.")
        }
        if (action == KeyboardAction.REPLY && chat == null && plan.input.isBlank()) {
            return note("No message to reply to. Anu sees the messages this app shows in its notifications.")
        }
        mutable.value = AssistState.Working(action)
        running = scope.launch {
            try {
                write(action, plan, chat, instruction)
            } catch (e: CancellationException) {
                if (mutable.value is AssistState.Working) mutable.value = AssistState.Idle
                throw e
            }
        }
    }

    private suspend fun write(action: KeyboardAction, plan: EditPlan, chat: ChatContext?, instruction: String) {
        val writing = action.writing
        val byPhone = writing?.system?.let { system?.write(it, plan.input) }
        if (byPhone != null && system != null) {
            val result = WritingOutput.clean(byPhone)
            if (result.isNotBlank()) {
                mutable.value = AssistState.Ready(action, result, plan, tier = null, engine = system.name)
                return
            }
        }
        val block = if (writing?.usesMemory != false) memoryOrNull() else null
        val request = when {
            writing != null -> KeyboardPrompt.rewrite(writing, plan.input, block)
            action == KeyboardAction.CONTINUE -> KeyboardPrompt.continuation(plan.input, block)
            action == KeyboardAction.EXPAND -> KeyboardPrompt.instructed(KeyboardPrompt.EXPAND, plan.input, block)
            action == KeyboardAction.EMOJI -> KeyboardPrompt.emoji(plan.input)
            action == KeyboardAction.ASK && plan.input.isBlank() -> KeyboardPrompt.compose(instruction, block)
            action == KeyboardAction.ASK -> KeyboardPrompt.instructed(instruction, plan.input, block)
            else -> KeyboardPrompt.reply(chat, plan.input, block)
        }
        val seen = plan.input + chat?.lines.orEmpty().joinToString("\n") { it.text }
        val route = RouteRequest(
            text = if (action == KeyboardAction.ASK) instruction else action.label,
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
                    val choices = if (action == KeyboardAction.EMOJI) KeyboardText.emoji(cleaned, KeyboardPrompt.EMOJI_COUNT) else emptyList()
                    mutable.value = when {
                        action == KeyboardAction.EMOJI && choices.isEmpty() -> AssistState.Note("No emoji came to mind. Try again.")
                        result.isBlank() -> AssistState.Note("Anu didn't come up with anything. Try again.")
                        else -> AssistState.Ready(
                            action, result, plan, event.tier, chat?.title.takeIf { action == KeyboardAction.REPLY },
                            choices = choices,
                            asked = instruction.takeIf { action == KeyboardAction.ASK },
                        )
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

    /** The text to commit for the preview ([choice] is the emoji tapped), or null when the field changed since. */
    fun accept(now: FieldText, choice: String? = null): Pair<EditPlan, String>? {
        val ready = mutable.value as? AssistState.Ready ?: return null
        // An emoji is inserted only as the one the user tapped.
        val picked = if (ready.choices.isEmpty()) ready.result else choice?.takeIf { it in ready.choices } ?: return null
        if (!KeyboardText.stillThere(ready.plan, now)) {
            mutable.value = AssistState.Note("Your text changed, so Anu left it as it is. Tap ${ready.action.label} again.")
            return null
        }
        mutable.value = AssistState.Idle
        return ready.plan to KeyboardText.output(ready.plan, picked)
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
