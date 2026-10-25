package io.github.sandeepvijayarao09.anu.feature.writing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.agent.CascadeEvent
import io.github.sandeepvijayarao09.anu.core.agent.TokenCalibration
import io.github.sandeepvijayarao09.anu.core.agent.TokenSample
import io.github.sandeepvijayarao09.anu.core.agent.Tokens
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.SystemWriter
import io.github.sandeepvijayarao09.anu.core.model.SystemWritingTask
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.router.TopModel
import io.github.sandeepvijayarao09.anu.core.security.PiiDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil

data class WritingUiState(
    val original: String = "",
    /** The other app can't take the text back, so the result can only be copied. */
    val readOnly: Boolean = false,
    /** What was asked, for example "Fix grammar". */
    val asked: String? = null,
    val result: String = "",
    val tier: Tier? = null,
    /** The phone's own writing model that wrote [result], such as "Gemini Nano", instead of a [tier] (ADR-116). */
    val engine: String? = null,
    val busy: Boolean = false,
    /** A status line, such as "Trying Pro…", or why it failed. */
    val note: String? = null,
    val done: Boolean = false,
    val failed: Boolean = false,
    /** The task needs the cloud, and the user hasn't said yes yet (§12.2). */
    val consentNeeded: Boolean = false,
) {
    val tooLong: Boolean get() = original.length > WritingPrompt.MAX_CHARS
}

/**
 * One writing task on text selected in another app (§13, ADR-042). It runs through the router's
 * cascade with no tools: on the phone by default, repaired or escalated if the result is empty (P6),
 * and in the cloud only after the user says yes.
 */
class WritingViewModel(
    private val cascade: Cascade,
    private val device: () -> DeviceState,
    private val memory: suspend () -> String? = { null },
    private val tokens: TokenCalibration = TokenCalibration.NONE,
    /** The phone's own writing model, tried first for the built-in actions (ADR-116). */
    private val system: SystemWriter? = null,
) : ViewModel() {

    private data class Task(
        val asked: String,
        val instruction: String,
        val usesMemory: Boolean,
        val precise: Boolean,
        val system: SystemWritingTask? = null,
    )

    private val mutable = MutableStateFlow(WritingUiState())
    val state: StateFlow<WritingUiState> = mutable.asStateFlow()

    private var running: Job? = null
    private var last: Task? = null

    fun start(text: String, readOnly: Boolean) {
        mutable.value = WritingUiState(original = text, readOnly = readOnly)
    }

    fun run(action: WritingAction) = go(Task(action.label, action.instruction, action.usesMemory, action.precise, action.system))

    /** The user's own instruction, such as "Translate to Spanish". */
    fun runCustom(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isNotEmpty()) go(Task(trimmed, trimmed, usesMemory = true, precise = false))
    }

    fun retryWithPro() {
        last?.let { go(it, pinned = Tier.PRO) }
    }

    fun approveCloud() {
        last?.let { go(it, cloudConsented = true) }
    }

    fun declineCloud() = mutable.update {
        it.copy(consentNeeded = false, busy = false, note = "Kept on this phone. Nothing was sent.")
    }

    fun stop() {
        running?.cancel()
    }

    private fun go(task: Task, pinned: Tier? = null, cloudConsented: Boolean = false) {
        val text = state.value.original
        if (text.isBlank() || state.value.tooLong) return
        running?.cancel()
        last = task
        mutable.update {
            it.copy(asked = task.asked, result = "", tier = null, engine = null, busy = true, note = null, done = false, failed = false, consentNeeded = false)
        }
        running = viewModelScope.launch {
            try {
                // Retry with Pro and the cloud are Gemma's; a first try goes to the phone's own model where it can.
                val byPhone = if (pinned == null && !cloudConsented) task.system?.let { system?.write(it, text) } else null
                if (byPhone != null && system != null) {
                    mutable.update { it.copy(result = WritingOutput.clean(byPhone), engine = system.name, note = null, done = true) }
                    return@launch
                }
                val block = if (task.usesMemory) memoryOrNull() else null
                val request = WritingPrompt.request(text, task.instruction, block, task.precise)
                val route = RouteRequest(
                    text = task.instruction,
                    skill = Skill.WRITING,
                    // Selected text can hold anything personal, so it is at least S1 (§12.1).
                    dataClasses = setOf(DataClass.PERSONAL) + PiiDetector.dataClasses(text),
                    promptTokens = ceil(Tokens.ofRequest(request) * tokens.scale).toInt(),
                    pinnedTier = pinned,
                    cloudConsented = cloudConsented,
                )
                cascade.run(route, device(), request, WritingOutput.validator).collect { event ->
                    when (event) {
                        is CascadeEvent.Routed -> mutable.update { it.copy(tier = event.decision.tier) }
                        is CascadeEvent.TextDelta -> mutable.update { it.copy(result = it.result + event.text) }
                        is CascadeEvent.Discarded -> mutable.update { it.copy(result = "", note = "Trying again…") }
                        is CascadeEvent.Escalated -> mutable.update { it.copy(tier = event.to, note = "Trying ${label(event.to)}…") }
                        is CascadeEvent.ConsentNeeded -> mutable.update { it.copy(consentNeeded = true, busy = false) }
                        is CascadeEvent.Completed -> {
                            event.usage?.let { usage ->
                                tokens.observe(
                                    TokenSample(
                                        estimated = Tokens.ofRequest(request) + Tokens.of(ChatMessage.Model(event.text)),
                                        counted = usage.contextTokens,
                                        onDevice = event.tier != Tier.CLOUD,
                                    ),
                                )
                            }
                            mutable.update { it.copy(result = WritingOutput.clean(event.text), tier = event.tier, note = null, done = true) }
                        }
                        is CascadeEvent.Failed -> mutable.update {
                            it.copy(result = "", failed = true, note = friendly(event.reasons))
                        }
                    }
                }
            } catch (e: CancellationException) {
                mutable.update { it.copy(note = "Stopped") }
                throw e
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun memoryOrNull(): String? = try {
        memory()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        fun label(state: WritingUiState): String? = state.engine?.let { "On-device · $it" } ?: state.tier?.let(::label)

        fun label(tier: Tier) = when (tier) {
            Tier.BASE -> "On-device · Base"
            Tier.PRO -> "On-device · Pro"
            Tier.CLOUD -> TopModel.current.label
        }

        private fun friendly(reasons: List<String>): String = when {
            reasons.any { it == "no model tier is available" } -> "No model is ready yet. Download one in Anu's Settings."
            else -> "Anu couldn't do that: ${reasons.lastOrNull() ?: "unknown error"}"
        }
    }
}
