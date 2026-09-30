package io.github.sandeepvijayarao09.anu.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainStatus
import io.github.sandeepvijayarao09.anu.core.model.PromptLab
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.model.labRequest
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuSegmented
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuSliderRow
import io.github.sandeepvijayarao09.anu.core.ui.AnuSwitch
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextField
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.SectionHeader
import io.github.sandeepvijayarao09.anu.core.ui.markdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** The prompt lab's starting points (G7): each fills in an instruction, which stays editable. */
enum class LabTemplate(val label: String, val options: List<String>) {
    FREE("Free form", emptyList()),
    REWRITE("Rewrite", listOf("Formal", "Friendly", "Shorter", "Clearer")),
    SUMMARIZE("Summarize", listOf("Key points", "Paragraph", "One line")),
    CODE("Code", listOf("Kotlin", "Python", "JavaScript", "SQL")),
    ;

    /** The instruction this template writes for [option]. */
    fun instruction(option: String?): String = when (this) {
        FREE -> ""
        REWRITE -> "Rewrite the user's text to be ${option.orEmpty().lowercase()}. Keep its meaning, names and numbers. Reply with the new text only."
        SUMMARIZE -> when (option) {
            "Key points" -> "Summarize the user's text as a few short bullet points. Reply with the summary only."
            "One line" -> "Summarize the user's text in one sentence. Reply with the sentence only."
            else -> "Summarize the user's text in one short paragraph. Reply with the summary only."
        }
        CODE -> "Write $option code for what the user asks. Reply with the code, then one short sentence about it."
    }
}

/** What the lab shows (G7, ADR-080). Nothing here is saved: leaving the lab forgets it. */
data class LabState(
    val template: LabTemplate = LabTemplate.FREE,
    val option: String? = null,
    val instruction: String = "",
    val prompt: String = "",
    val models: List<BrainId> = emptyList(),
    val model: BrainId? = null,
    /** Gemma's published defaults, as chat uses (§5). */
    val temperature: Float = 1.0f,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val maxTokens: Int = 512,
    val thinking: Boolean = false,
    val running: Boolean = false,
    /** What the model is doing before its first word, such as "Getting Gemma 4 E4B ready…". */
    val waiting: String? = null,
    val output: String = "",
    val thought: String = "",
    val speed: String? = null,
    val error: String? = null,
    /** The run was stopped, so the output is partial. */
    val stopped: Boolean = false,
) {
    val canRun: Boolean get() = !running && model != null && prompt.isNotBlank()
}

/**
 * The prompt lab (G7, ADR-080): one prompt at a time on a model on this phone, with the instruction and sampling the
 * user chooses. It uses [PromptLab] only, which has no memory, tools, connectors or history, and it keeps nothing.
 */
class LabViewModel(
    private val lab: PromptLab,
    /** What the on-device models are doing, to name the wait before the first word. */
    private val status: StateFlow<BrainStatus>? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) : ViewModel() {

    private val mutable = MutableStateFlow(LabState())
    val state: StateFlow<LabState> = mutable.asStateFlow()
    private var running: Job? = null

    init {
        refresh()
        status?.let { now -> viewModelScope.launch { now.collect { value -> mutable.update { it.copy(waiting = if (it.running && it.output.isEmpty()) waitLine(value) else it.waiting) } } } }
    }

    /** Reads which models are installed, keeping the chosen one if it still is. Pro first, as the better writer. */
    fun refresh() {
        val installed = lab.models()
        mutable.update { state -> state.copy(models = installed, model = state.model?.takeIf { it in installed } ?: installed.maxByOrNull { if (it == BrainId.PRO) 1 else 0 }) }
    }

    fun chooseTemplate(template: LabTemplate) {
        val option = template.options.firstOrNull()
        mutable.update { it.copy(template = template, option = option, instruction = template.instruction(option)) }
    }

    fun chooseOption(option: String) = mutable.update { it.copy(option = option, instruction = it.template.instruction(option)) }

    fun setInstruction(text: String) = mutable.update { it.copy(instruction = text) }

    fun setPrompt(text: String) = mutable.update { it.copy(prompt = text) }

    fun chooseModel(id: BrainId) = mutable.update { it.copy(model = id) }

    fun setTemperature(value: Float) = mutable.update { it.copy(temperature = (value * 20).roundToInt() / 20f) }

    fun setTopK(value: Int) = mutable.update { it.copy(topK = value.coerceIn(1, 100)) }

    fun setTopP(value: Float) = mutable.update { it.copy(topP = (value * 20).roundToInt() / 20f) }

    fun setMaxTokens(value: Int) = mutable.update { it.copy(maxTokens = value.coerceIn(MIN_TOKENS, MAX_TOKENS)) }

    fun setThinking(on: Boolean) = mutable.update { it.copy(thinking = on) }

    /** Runs the prompt. The answer streams in; the reasoning, with thinking on, comes apart from it. */
    fun run() {
        val state = mutable.value
        val model = state.model ?: return
        if (!state.canRun) return
        // Temperature 0 is greedy: the same answer every time (P6).
        val sampling = if (state.temperature == 0f) Sampling.Precise else Sampling(state.temperature.toDouble(), state.topP.toDouble(), state.topK)
        val request = labRequest(state.instruction, state.prompt, sampling, state.maxTokens, state.thinking)
        mutable.update { it.copy(running = true, output = "", thought = "", speed = null, error = null, stopped = false, waiting = status?.value?.let(::waitLine)) }
        running = viewModelScope.launch {
            val sent = nanoTime()
            var first: Long? = null
            try {
                lab.run(model, request).collect { event ->
                    when (event) {
                        is BrainEvent.TextDelta -> {
                            if (event.thought) {
                                mutable.update { it.copy(thought = it.thought + event.text, waiting = null) }
                            } else {
                                if (first == null) first = nanoTime()
                                mutable.update { it.copy(output = it.output + event.text, waiting = null) }
                            }
                        }
                        is BrainEvent.Usage -> {
                            val speed = Speed(
                                firstWordMs = first?.let { (it - sent) / 1_000_000 },
                                readPerSecond = rate(event.inputTokens, event.readMs),
                                writePerSecond = rate(event.outputTokens, event.writeMs),
                                counted = event.counted,
                            )
                            mutable.update { it.copy(speed = speedLine(speed, Locale.US).replaceFirstChar(Char::uppercase)) }
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                mutable.update { it.copy(stopped = true) }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = "The model couldn't answer: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                mutable.update { it.copy(running = false, waiting = null) }
            }
        }
    }

    fun stop() {
        running?.cancel()
    }

    private fun rate(tokens: Int?, ms: Long?): Double? = if (tokens == null || tokens <= 0 || ms == null || ms <= 0) null else tokens * 1_000.0 / ms

    private fun waitLine(status: BrainStatus): String? = when (status) {
        BrainStatus.Idle -> null
        is BrainStatus.Loading -> "Getting ${modelName(status.id)} ready…"
        is BrainStatus.SettingUpGpu -> "Setting up the GPU. First time only, about a minute."
        is BrainStatus.Switching -> "Switching to ${modelName(status.id)}…"
        is BrainStatus.Reading -> "Reading…"
    }

    companion object {
        const val MIN_TOKENS = 64
        const val MAX_TOKENS = 1_536
    }
}

/**
 * The prompt lab's page (G7, ADR-080; DESIGN.md "Prompt lab"): a template and its instruction, the prompt, the model
 * and its sampling, then Run and the answer with its speed and Copy. With no model installed it says so.
 */
@Composable
internal fun LabPage(lab: LabViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit, onOpenModels: () -> Unit) {
    val state by lab.state.collectAsStateWithLifecycle()
    var sampling by rememberSaveable { mutableStateOf(false) }
    // A model installed since the lab opened shows up when the page comes back.
    LifecycleResumeEffect(Unit) {
        lab.refresh()
        onPauseOrDispose {}
    }
    AnuPage(
        "Prompt lab", sentToday, onOpenPrivacy, back = BACK, onBack = onBack,
        subtitle = "Try a prompt on the models on this phone. The lab has no memory, tools or history, and keeps nothing.",
    ) {
        if (state.models.isEmpty()) {
            item(key = "none") {
                Column {
                    Group { GroupRow("Download a model to use the lab", chevron = true, onClick = onOpenModels) }
                    GroupFooter("The lab runs Gemma on this phone only.")
                }
            }
            return@AnuPage
        }
        item(key = "template") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnuSegmented(LabTemplate.entries.map { it.label }, state.template.ordinal, onSelect = { lab.chooseTemplate(LabTemplate.entries[it]) })
                if (state.template.options.isNotEmpty()) {
                    AnuSegmented(state.template.options, state.template.options.indexOf(state.option).coerceAtLeast(0), onSelect = { lab.chooseOption(state.template.options[it]) })
                }
                AnuTextField(state.instruction, lab::setInstruction, label = "Instruction", placeholder = "Optional, such as \"Answer in one paragraph\"", singleLine = false, minLines = 2)
                AnuTextField(state.prompt, lab::setPrompt, label = "Prompt", placeholder = "What to send the model", singleLine = false, minLines = 4)
            }
        }
        item(key = "model") {
            Column {
                SectionHeader("Model")
                Column(Modifier.padding(horizontal = 20.dp)) {
                    AnuSegmented(state.models.map(::modelName), state.models.indexOf(state.model).coerceAtLeast(0), onSelect = { lab.chooseModel(state.models[it]) })
                }
            }
        }
        item(key = "sampling") {
            Column {
                SectionHeader("Sampling")
                Group {
                    GroupRow(
                        "Temperature, top-K, top-P, length",
                        value = String.format(Locale.US, "%.2f · %d · %.2f · %d", state.temperature, state.topK, state.topP, state.maxTokens),
                        chevron = true,
                        onClick = { sampling = !sampling },
                    )
                    if (sampling) {
                        GroupDivider()
                        AnuSliderRow("Temperature", state.temperature, lab::setTemperature, 0f..2f, String.format(Locale.US, "%.2f", state.temperature), steps = 39)
                        AnuSliderRow("Top-K", state.topK.toFloat(), { lab.setTopK(it.roundToInt()) }, 1f..100f, "${state.topK}", steps = 98)
                        AnuSliderRow("Top-P", state.topP, lab::setTopP, 0.05f..1f, String.format(Locale.US, "%.2f", state.topP), steps = 18)
                        AnuSliderRow(
                            "Longest answer", state.maxTokens.toFloat(), { lab.setMaxTokens((it / 64).roundToInt() * 64) },
                            LabViewModel.MIN_TOKENS.toFloat()..LabViewModel.MAX_TOKENS.toFloat(), "${state.maxTokens} tokens", steps = 22,
                        )
                    }
                    GroupDivider()
                    GroupRow(
                        "Think first",
                        modifier = Modifier.semantics { toggleableState = ToggleableState(state.thinking) },
                        subtitle = "The model reasons before it answers",
                        onClick = { lab.setThinking(!state.thinking) },
                        trailing = { AnuSwitch(state.thinking, onCheckedChange = null) },
                    )
                }
                GroupFooter("Temperature 0 gives the same answer every time. Gemma's defaults are 1.00, 64 and 0.95.")
            }
        }
        item(key = "run") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (state.running) {
                    AnuButton("Stop", onClick = lab::stop, modifier = Modifier.fillMaxWidth(), secondary = true)
                } else {
                    AnuButton("Run", onClick = lab::run, modifier = Modifier.fillMaxWidth(), enabled = state.canRun)
                }
            }
        }
        if (state.running || state.output.isNotEmpty() || state.error != null) {
            item(key = "output") { LabOutput(state) }
        }
    }
}

/** The answer: the reasoning collapsed above it, then the words, whether it was stopped, the speed and Copy. Both render Markdown. */
@Composable
private fun LabOutput(state: LabState) {
    val colors = AnuTheme.colors
    val context = LocalContext.current
    var reasoning by rememberSaveable { mutableStateOf(false) }
    Column {
        SectionHeader("Answer")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.thought.isNotEmpty()) {
                Row(
                    Modifier.heightIn(min = 48.dp).clickable(onClickLabel = if (reasoning) "Hide the reasoning" else "Show the reasoning") { reasoning = !reasoning },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Thought it through", style = AnuTheme.type.caption, color = colors.textMuted)
                    Icon(if (reasoning) AnuIcons.ChevronDown else AnuIcons.ChevronRight, contentDescription = null, Modifier.padding(start = 2.dp).size(12.dp), tint = colors.textFaint)
                }
                if (reasoning) {
                    Text(
                        markdown(state.thought.trim()),
                        Modifier.fillMaxWidth().clip(AnuShapes.md).background(colors.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
                        style = AnuTheme.type.callout,
                        color = colors.textMuted,
                    )
                }
            }
            when {
                state.error != null -> Text(state.error, style = AnuTheme.type.body, color = colors.errorText)
                state.output.isNotEmpty() -> SelectionContainer {
                    // Shown as the chat shows answers; Copy copies what the model wrote (ADR-090).
                    Text(markdown(state.output.trim()), Modifier.fillMaxWidth(), style = AnuTheme.type.body, color = colors.text)
                }
                else -> Text(state.waiting ?: "…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = AnuTheme.type.callout, color = colors.textMuted)
            }
            if (state.stopped && state.output.isNotEmpty()) Text("Stopped before the end", style = AnuTheme.type.caption, color = colors.textMuted)
            if (!state.running && state.output.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    state.speed?.let { Text(it, Modifier.weight(1f), style = AnuTheme.type.mono, color = colors.textMuted) }
                    AnuTextButton("Copy", onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Anu", state.output.trim())) })
                }
            }
        }
    }
}
