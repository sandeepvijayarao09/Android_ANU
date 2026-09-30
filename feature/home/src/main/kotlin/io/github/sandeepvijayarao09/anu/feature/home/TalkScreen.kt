package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.ui.semantics.selected
import io.github.sandeepvijayarao09.anu.core.ui.AnuPill
import io.github.sandeepvijayarao09.anu.core.model.Recording
import io.github.sandeepvijayarao09.anu.core.model.clock
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.DetailPanel
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.router.Guard
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.PrivacyDot
import io.github.sandeepvijayarao09.anu.core.ui.PrivacyIndicator
import io.github.sandeepvijayarao09.anu.core.ui.Sphere
import io.github.sandeepvijayarao09.anu.core.ui.SphereState
import io.github.sandeepvijayarao09.anu.core.ui.anuCard
import java.time.LocalTime

private fun VoicePhase.sphere(): SphereState = when (this) {
    VoicePhase.IDLE -> SphereState.IDLE
    VoicePhase.LISTENING -> SphereState.LISTENING
    VoicePhase.THINKING -> SphereState.THINKING
    VoicePhase.SPEAKING -> SphereState.SPEAKING
}

/**
 * The Talk page (DESIGN.md, ADR-043): one surface for voice and typing.
 * - Empty: the sphere, a greeting and two suggestions.
 * - Talking: the sphere moves into the app bar and the thread fills the page, with receipts written from
 *   the broker's record under each answer.
 * - Listening: a large sphere following your voice, and your words as you say them.
 * - The privacy indicator is always in the corner: [sentToday] things have left the phone today, and
 *   [awayPulse] changes each time one more does.
 */
@Composable
fun TalkScreen(
    viewModel: ChatViewModel,
    micGranted: Boolean,
    /** Asks for the microphone, then runs what the user was doing once it's allowed: talking, or a voice note. */
    onRequestMic: (then: () -> Unit) -> Unit,
    sentToday: Int,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    awayPulse: Int = 0,
    /** Opens a fact in Memory, or Memory itself for null, from "About this answer". */
    onOpenMemory: (Long?) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AnuTheme.colors
    val activity = LocalActivity.current
    // Leaving Anu ends a voice note (ADR-078); rotating or a theme change only rebuilds the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) viewModel.leftAnu() }
    var input by rememberSaveable { mutableStateOf("") }
    val typing = remember { FocusRequester() }
    val talk = { if (micGranted) viewModel.toggleListening() else onRequestMic(viewModel::toggleListening) }
    val list = rememberLazyListState()
    // The system photo picker: Anu sees only the photo chosen, and keeps a smaller copy (G2, ADR-075).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) { readPhoto(context.contentResolver, uri) }
                if (jpeg != null) viewModel.attach(jpeg) else viewModel.photoUnreadable()
            }
        }
    }
    // A hairline under the app bar once the thread scrolls beneath it.
    val scrolled by remember { derivedStateOf { list.canScrollBackward } }
    // The "+" sheet: a photo or a voice note (design review D9, ADR-078).
    var adding by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(colors.background).imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.bubbles.isNotEmpty() || state.listening) {
                Sphere(state.voice.sphere(), 26.dp, level = state.level, awayPulse = awayPulse, errorPulse = state.failures)
                Spacer(Modifier.width(10.dp))
            }
            Text("Anu", Modifier.weight(1f), style = AnuTheme.type.wordmark, color = colors.text)
            PrivacyIndicator(sentToday, onOpenPrivacy)
        }
        val showThread = !state.listening && state.bubbles.isNotEmpty()
        HorizontalDivider(thickness = 0.5.dp, color = if (showThread && scrolled) colors.separator else Color.Transparent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.listening -> Listening(state)
                state.bubbles.isEmpty() -> Welcome(state, awayPulse, onSuggestion = { viewModel.send(it) }, onTalk = talk)
                else -> Thread(state, list, onRetry = viewModel::retryWithPro, onAskGemini = viewModel::askGemini, onAbout = viewModel::openAbout, loadPhoto = viewModel::photo)
            }
        }
        if (state.listening) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnuTextButton("Type instead", onClick = { viewModel.toggleListening(); runCatching { typing.requestFocus() } })
                Box(
                    Modifier.size(64.dp).clip(AnuShapes.full).background(colors.surface2).clickable(onClickLabel = "Stop listening") { viewModel.toggleListening() },
                    contentAlignment = Alignment.Center,
                ) { Icon(AnuIcons.Stop, contentDescription = "Stop", Modifier.size(22.dp), tint = colors.text) }
                AnuTextButton(if (state.muted) "Unmute" else "Mute", onClick = viewModel::toggleMute, color = colors.textMuted)
            }
        } else {
            VoiceProblem(state, onGetSpeech = viewModel::downloadLanguagePack)
            state.attachmentNote?.let { note ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(note, Modifier.weight(1f), style = AnuTheme.type.caption, color = AnuTheme.colors.textMuted)
                    AnuTextButton("OK", onClick = viewModel::dismissAttachmentNote)
                }
            }
            val recordingFor = state.recordingSeconds
            if (recordingFor != null) {
                RecordingBar(recordingFor, state.level, onCancel = viewModel::cancelRecording, onStop = viewModel::finishRecording)
            } else {
                Composer(
                    value = input,
                    onValueChange = {
                        if (input.isEmpty() && it.isNotEmpty()) viewModel.typingStarted()
                        input = it
                    },
                    busy = state.busy,
                    onSend = { viewModel.send(input); input = "" },
                    onStop = viewModel::stop,
                    onTalk = talk,
                    focus = typing,
                    attachment = state.attachment,
                    onAttach = { adding = true },
                    onRemoveAttachment = viewModel::removeAttachment,
                    recording = state.recording,
                    onRemoveRecording = viewModel::removeRecording,
                )
            }
        }
    }
    if (adding) {
        AddSheet(
            canHear = viewModel.canHear(),
            onPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onVoiceNote = { if (micGranted) viewModel.startRecording() else onRequestMic(viewModel::startRecording) },
            onDismiss = { adding = false },
        )
    }
    ConversationSheets(state, viewModel::approve, viewModel::deny, viewModel::revise, viewModel::approveCloud, viewModel::declineCloud)
    state.about?.let { about -> AboutSheet(about, onOpenMemory, onOpenPrivacy, onDismiss = viewModel::closeAbout) }
}

@Composable
private fun Welcome(state: ChatUiState, awayPulse: Int, onSuggestion: (String) -> Unit, onTalk: () -> Unit) {
    val colors = AnuTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Sphere(state.voice.sphere(), 168.dp, level = state.level, awayPulse = awayPulse, errorPulse = state.failures, shadow = true, onClick = onTalk)
        Spacer(Modifier.height(28.dp))
        Text(greeting(), style = AnuTheme.type.title.copy(fontSize = 28.sp, lineHeight = 34.sp), color = colors.text)
        Text(
            if (state.voiceAvailable) "Ask anything, or tap the sphere to talk." else "Ask anything. It stays on your phone.",
            Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp),
            style = AnuTheme.type.callout,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        SUGGESTIONS.forEach { (ask, where) ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).anuCard().clickable { onSuggestion(ask) }.padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Text(ask, style = AnuTheme.type.callout, color = colors.text)
                Text(where, style = AnuTheme.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = colors.textMuted)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private val SUGGESTIONS = listOf(
    "What's on my calendar today?" to "Calendar",
    "Any new messages?" to "Messages",
)

private fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun Listening(state: ChatUiState) {
    val colors = AnuTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(44.dp))
        Sphere(SphereState.LISTENING, 200.dp, level = state.level, errorPulse = state.failures, shadow = true)
        Spacer(Modifier.height(36.dp))
        Text(
            state.heard.ifEmpty { "Say something…" },
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            style = AnuTheme.type.liveSpeech,
            color = if (state.heard.isEmpty()) colors.textFaint else colors.text,
        )
        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelBars(state.level)
            Text("Listening", style = AnuTheme.type.callout, color = colors.textMuted)
        }
    }
}

/** Five bars that follow the voice level. */
@Composable
private fun LevelBars(level: Float) {
    val colors = AnuTheme.colors
    Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.4f, 0.85f, 0.6f, 1f, 0.45f).forEach { shape ->
            Box(Modifier.width(3.dp).height((4f + 12f * shape * (0.35f + 0.65f * level)).dp).clip(AnuShapes.full).background(colors.textMuted))
        }
    }
}

@Composable
private fun Thread(
    state: ChatUiState,
    list: LazyListState,
    onRetry: () -> Unit,
    onAskGemini: () -> Unit,
    onAbout: (Long) -> Unit,
    loadPhoto: suspend (Long) -> ByteArray?,
) {
    LaunchedEffect(state.bubbles.size, state.bubbles.lastOrNull()?.text?.length, state.bubbles.lastOrNull()?.actions?.size) {
        if (state.bubbles.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.trimmed) {
            item(key = "trimmed") {
                Text(
                    "Long chat: Anu reads the latest messages. What it learned earlier is in Memory.",
                    style = AnuTheme.type.caption,
                    color = AnuTheme.colors.textMuted,
                )
            }
        }
        items(state.bubbles, key = { it.id }) { bubble ->
            if (bubble.fromUser) {
                YourTurn(bubble, loadPhoto)
            } else {
                val last = bubble == state.bubbles.last()
                AnuTurn(
                    bubble,
                    choices = if (last && !state.busy) retryChoices(bubble, state.cloudAllowed) else emptyList(),
                    onRetry = onRetry,
                    onAskGemini = onAskGemini,
                    onAbout = bubble.details?.let { { onAbout(bubble.id) } },
                )
            }
        }
    }
}

@Composable
private fun YourTurn(bubble: Bubble, loadPhoto: suspend (Long) -> ByteArray?) {
    val colors = AnuTheme.colors
    // Your photo sits in your turn, above your words (design review 3A).
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bubble.photos.forEach { photo -> PhotoView(photo, loadPhoto) }
        bubble.voice?.let { clip -> VoiceClipView(clip) }
        // A question about the screen keeps only which app it was; the picture is never kept (ADR-083).
        bubble.screenApp?.let { app ->
            Text("Your screen · $app", style = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal), color = colors.textMuted)
        }
        if (bubble.text.isNotEmpty()) {
            Text(
                bubble.text,
                Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp)).background(colors.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
                style = AnuTheme.type.body,
                color = colors.text,
            )
        }
    }
}

/** What the last answer offers to try again (design review D21). */
internal enum class RetryChoice { WITH_PRO, ON_PHONE, GEMINI }

/**
 * The choices under the last answer (design review D21, ADR-072). A lighter answer from Base offers Gemma 4 E4B.
 * While the phone is guarded, a lighter or failed answer offers the phone and Gemini side by side when the cloud
 * can be asked, so keeping it private is always one tap. A shortened Pro answer can only get better from Gemini.
 */
internal fun retryChoices(bubble: Bubble, cloudAllowed: Boolean): List<RetryChoice> {
    if (bubble.fromUser || bubble.streaming || bubble.tier == null || bubble.details?.direct == true) return emptyList()
    val guard = bubble.details?.guard ?: bubble.guard?.takeIf { bubble.guarded || bubble.failed }
    return when {
        guard != null && bubble.failed -> if (cloudAllowed) listOf(RetryChoice.ON_PHONE, RetryChoice.GEMINI) else listOf(RetryChoice.ON_PHONE)
        guard != null && bubble.tier == Tier.BASE -> if (cloudAllowed) listOf(RetryChoice.ON_PHONE, RetryChoice.GEMINI) else listOf(RetryChoice.WITH_PRO)
        guard != null && bubble.tier == Tier.PRO -> if (cloudAllowed) listOf(RetryChoice.GEMINI) else emptyList()
        bubble.failed -> emptyList()
        bubble.tier == Tier.BASE -> listOf(RetryChoice.WITH_PRO)
        else -> emptyList()
    }
}

/**
 * Why the guard changed this answer, as one quiet line (design review D22): a lighter model while warm or on a
 * low battery, or an answer kept short. Null when it didn't.
 */
internal fun guardLine(bubble: Bubble): String? {
    val guard = bubble.details?.guard ?: bubble.guard?.takeIf { bubble.guarded } ?: return null
    return when {
        bubble.details?.cut == true -> if (guard == Guard.HEAT) "Kept short while the phone cools" else "Kept short to save battery"
        bubble.tier == Tier.BASE -> if (guard == Guard.HEAT) "Lighter model while the phone is warm" else "Lighter model while the battery is low"
        else -> null
    }
}

@Composable
internal fun AnuTurn(
    bubble: Bubble,
    choices: List<RetryChoice> = emptyList(),
    onRetry: () -> Unit = {},
    onAskGemini: () -> Unit = {},
    onAbout: (() -> Unit)? = null,
) {
    val colors = AnuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (bubble.thought.isNotEmpty() && bubble.text.isNotEmpty()) Reasoning(bubble.thought)
        when {
            bubble.text.isNotEmpty() -> Text(
                markdown(bubble.text),
                // Long-press is a shortcut to "About this answer"; TalkBack offers it as an action (design review D8).
                if (onAbout == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(onAbout) { detectTapGestures(onLongPress = { onAbout() }) }
                        .semantics { customActions = listOf(CustomAccessibilityAction("About this answer") { onAbout(); true }) }
                },
                style = AnuTheme.type.body,
                color = if (bubble.failed) colors.errorText else colors.text,
            )
            // TalkBack hears each new wait line once (design review D10).
            bubble.streaming -> Text(bubble.note ?: "…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = AnuTheme.type.callout, color = colors.textMuted)
        }
        if (bubble.text.isNotEmpty() && bubble.note != null) Text(bubble.note, style = AnuTheme.type.caption, color = colors.textMuted)
        bubble.actions.forEach { ReceiptRow(it) }
        LinkButtons(bubble.links)
        if (!bubble.streaming && bubble.text.isNotEmpty()) {
            bubble.tier?.let { tier -> Caption(tier, onAbout, direct = bubble.details?.direct == true) }
            guardLine(bubble)?.let { line -> Text(line, style = AnuTheme.type.caption, color = colors.textMuted) }
            if (choices.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    choices.forEach { choice ->
                        when (choice) {
                            RetryChoice.WITH_PRO -> AnuTextButton("Retry with ${Tier.PRO.model}", onClick = onRetry)
                            RetryChoice.ON_PHONE -> AnuTextButton("Retry on this phone", onClick = onRetry)
                            RetryChoice.GEMINI -> Row(verticalAlignment = Alignment.CenterVertically) {
                                PrivacyDot(away = true, size = 6.dp)
                                Spacer(Modifier.width(6.dp))
                                AnuTextButton("Ask Gemini", onClick = onAskGemini)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The model's reasoning, collapsed above its answer (G4, ADR-077): "Thought it through ›" opens it. How long it took
 * is in About this answer, since speed stays out of the thread (DESIGN.md).
 */
@Composable
private fun Reasoning(thought: String) {
    val colors = AnuTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Hide the reasoning" else "Show the reasoning") { open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Thought it through", style = AnuTheme.type.caption, color = colors.textMuted)
        Icon(
            if (open) AnuIcons.ChevronDown else AnuIcons.ChevronRight, contentDescription = null,
            Modifier.padding(start = 2.dp).size(12.dp), tint = colors.textFaint,
        )
    }
    if (open) {
        Text(
            thought.trim(),
            Modifier.fillMaxWidth().clip(AnuShapes.md).background(colors.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
            style = AnuTheme.type.callout,
            color = colors.textMuted,
        )
    }
}

/**
 * The line under an answer naming its model, with its privacy dot (DESIGN.md). With details saved, it is a
 * 48dp button that opens "About this answer" (design review D8).
 */
@Composable
private fun Caption(tier: Tier, onAbout: (() -> Unit)?, direct: Boolean = false) {
    val colors = AnuTheme.colors
    Row(
        Modifier.heightIn(min = 48.dp).then(if (onAbout != null) Modifier.clickable(onClickLabel = "About this answer", onClick = onAbout) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrivacyDot(away = tier == Tier.CLOUD, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        // A phone command ran with no model (design review D20).
        Text(if (direct) "On device · no model needed" else tier.label, style = AnuTheme.type.caption, color = colors.textMuted)
        if (onAbout != null) Icon(AnuIcons.ChevronRight, contentDescription = null, Modifier.padding(start = 2.dp).size(12.dp), tint = colors.textFaint)
    }
}

/** A receipt (DESIGN.md): the tool's own words for what happened, and where it happened. */
@Composable
internal fun ReceiptRow(receipt: Receipt) {
    val colors = AnuTheme.colors
    Row(
        Modifier.fillMaxWidth().anuCard().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(colors.surface2), contentAlignment = Alignment.Center) {
            Icon(iconFor(receipt), contentDescription = null, Modifier.size(16.dp), tint = colors.text)
        }
        Column(Modifier.weight(1f)) {
            Text(receipt.summary, style = AnuTheme.type.callout.copy(fontSize = 14.sp, lineHeight = 19.sp), color = colors.text)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (receipt.status) {
                    Receipt.Status.DONE -> {
                        PrivacyDot(away = receipt.leftPhone, size = 6.dp)
                        Text("${receipt.family} · ${if (receipt.leftPhone) "sent" else "on device"}", style = AnuTheme.type.caption, color = colors.textMuted)
                    }
                    Receipt.Status.NOT_DONE -> Text("Not done", style = AnuTheme.type.caption, color = colors.textMuted)
                    Receipt.Status.FAILED -> Text("Failed", style = AnuTheme.type.caption, color = colors.errorText)
                }
            }
        }
    }
}

private fun iconFor(receipt: Receipt) = when (receipt.family) {
    "Calendar" -> AnuIcons.Calendar
    "Contacts" -> AnuIcons.Person
    "Gmail" -> AnuIcons.Mail
    "Messages", "Text messages" -> AnuIcons.Message
    "Memory" -> AnuIcons.Memory
    "Store" -> AnuIcons.Bag
    "Service" -> AnuIcons.Service
    "Phone" -> AnuIcons.Settings
    else -> AnuIcons.Sparkle
}

/** Links a tool offered, such as "Finish at shoes.example.com". Nothing opens until the user taps (ADR-047). */
@Composable
internal fun LinkButtons(links: List<LinkAction>) {
    val uri = LocalUriHandler.current
    links.forEach { link -> AnuTextButton(link.label, onClick = { runCatching { uri.openUri(link.url) } }) }
}

/**
 * A voice note in your turn (G3, ADR-078): what it is and how long, then its words, muted while they're still being
 * written down. Its recording isn't kept, so there is nothing to play.
 */
@Composable
internal fun VoiceClipView(clip: VoiceClip) {
    val colors = AnuTheme.colors
    val label = voiceLabel(clip)
    Column(
        Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp)).background(colors.surface2).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AnuIcons.Mic, contentDescription = null, Modifier.size(14.dp), tint = colors.textMuted)
            Spacer(Modifier.width(6.dp))
            Text(label, style = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal), color = colors.textMuted)
        }
        when {
            clip.words.isNotEmpty() -> Text(clip.words, Modifier.padding(top = 4.dp), style = AnuTheme.type.body, color = if (clip.writing) colors.textMuted else colors.text)
            !clip.writing -> Text("Not written down", Modifier.padding(top = 4.dp), style = AnuTheme.type.caption, color = colors.textMuted)
        }
    }
}

/** "Voice note · 0:17", "Shared recording · 1:02 · Stopped early". */
internal fun voiceLabel(clip: VoiceClip): String =
    listOfNotNull(if (clip.shared) "Shared recording" else "Voice note", clock(clip.seconds), "Stopped early".takeIf { clip.interrupted }).joinToString(" · ")

/**
 * What the "+" adds (design review D9, ADR-078): a photo from the system picker, or a voice note, which needs a model
 * that can hear.
 */
@Composable
internal fun AddSheet(canHear: Boolean, onPhoto: () -> Unit, onVoiceNote: () -> Unit, onDismiss: () -> Unit) {
    AnuSheet(onDismiss) { close ->
        SheetTitle("Add to your message")
        DetailPanel(Modifier.padding(top = 14.dp)) {
            GroupRow("Photo", subtitle = "Choose one from your photos", icon = AnuIcons.Photo, onClick = { close(); onPhoto() })
            GroupRow(
                "Voice note",
                subtitle = if (canHear) "Record it, and Anu writes it down on this phone" else "Needs Gemma 4 on this phone",
                icon = AnuIcons.Mic,
                enabled = canHear,
                onClick = { close(); onVoiceNote() },
            )
        }
    }
}

/**
 * The composer while a voice note records (ADR-078): Cancel, the level and the time, and Stop, which keeps it for
 * the message. The sphere follows the voice too.
 */
@Composable
internal fun RecordingBar(seconds: Int, level: Float, onCancel: () -> Unit, onStop: () -> Unit) {
    val colors = AnuTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp).heightIn(min = 52.dp).anuCard(AnuShapes.xl)
            .padding(start = 4.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnuTextButton("Cancel", onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp), color = colors.textMuted)
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Recording a voice note, ${clock(seconds)}" },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LevelBars(level)
            Spacer(Modifier.width(10.dp))
            Text(clock(seconds), style = AnuTheme.type.mono.copy(fontSize = 15.sp, lineHeight = 20.sp), color = colors.text)
            Spacer(Modifier.width(8.dp))
            Text("Recording", style = AnuTheme.type.callout, color = colors.textMuted)
        }
        Box(
            Modifier.padding(start = 8.dp).size(42.dp).clip(AnuShapes.full).background(colors.primary).clickable(onClickLabel = "Stop recording", onClick = onStop),
            contentAlignment = Alignment.Center,
        ) { Icon(AnuIcons.Stop, contentDescription = "Stop recording", Modifier.size(18.dp), tint = colors.onPrimary) }
    }
}

/** Why voice didn't work, where it can't be missed, with the fix when there is one. */
@Composable
private fun VoiceProblem(state: ChatUiState, onGetSpeech: () -> Unit) {
    val note = state.voiceProblem ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(note, Modifier.weight(1f), style = AnuTheme.type.caption, color = AnuTheme.colors.textMuted)
        if (state.offerLanguagePack) AnuTextButton("Get on-device speech", onClick = onGetSpeech)
    }
}

/** The composer: a pill with the microphone, which becomes Send while you type and Stop while Anu works. */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onTalk: () -> Unit,
    focus: FocusRequester,
    attachment: ByteArray? = null,
    onAttach: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {},
    recording: Recording? = null,
    onRemoveRecording: () -> Unit = {},
) {
    val colors = AnuTheme.colors
    // The voice note to send, above the composer, with a way to take it back (ADR-078).
    recording?.let { voice ->
        val label = voiceLabel(VoiceClip(voice.seconds, voice.shared, voice.interrupted))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(AnuShapes.full).background(colors.surface2).padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AnuIcons.Mic, contentDescription = null, Modifier.size(16.dp), tint = colors.textMuted)
                Spacer(Modifier.width(6.dp))
                Text(label, style = AnuTheme.type.callout, color = colors.text)
            }
            Box(
                Modifier.size(48.dp).clickable(onClickLabel = "Remove voice note", onClick = onRemoveRecording),
                contentAlignment = Alignment.Center,
            ) { Icon(AnuIcons.Close, contentDescription = "Remove voice note", Modifier.size(18.dp), tint = colors.textMuted) }
        }
    }
    // The photo to send, above the composer, with a way to take it back.
    attachment?.let { jpeg ->
        val image = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            image?.let {
                Image(it, contentDescription = "Photo to send", Modifier.size(64.dp).clip(AnuShapes.md), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier.size(48.dp).clickable(onClickLabel = "Remove photo", onClick = onRemoveAttachment),
                contentAlignment = Alignment.Center,
            ) { Icon(AnuIcons.Close, contentDescription = "Remove photo", Modifier.size(18.dp), tint = colors.textMuted) }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp).heightIn(min = 52.dp).anuCard(AnuShapes.xl).padding(start = 4.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Adds a photo or a voice note (design review D9, ADR-078).
        Box(
            Modifier.size(48.dp).clip(AnuShapes.full).clickable(enabled = !busy, onClickLabel = "Add a photo or a voice note", onClick = onAttach),
            contentAlignment = Alignment.Center,
        ) { Icon(AnuIcons.Plus, contentDescription = "Add a photo or a voice note", Modifier.size(22.dp), tint = if (busy) colors.textFaint else colors.textMuted) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).focusRequester(focus),
            textStyle = AnuTheme.type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            maxLines = 5,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Ask Anu", style = AnuTheme.type.body, color = colors.textFaint)
                    inner()
                }
            },
        )
        val (icon, label, action) = when {
            busy -> Triple(AnuIcons.Stop, "Stop", onStop)
            value.isBlank() && attachment == null && recording == null -> Triple(AnuIcons.Mic, "Talk", onTalk)
            else -> Triple(AnuIcons.ArrowUp, "Send", onSend)
        }
        Box(
            Modifier.padding(start = 8.dp).size(42.dp).clip(AnuShapes.full).background(colors.primary).clickable(onClickLabel = label, onClick = action),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, Modifier.size(20.dp), tint = colors.onPrimary) }
    }
}

/**
 * The assistant card over any app (DESIGN.md, ADR-044): the sphere, what you asked, Anu's answer and its
 * receipts, and a plain statement that it isn't reading the screen. Tapping the card opens the app. It
 * draws no surface of its own: its window is the frosted card (ADR-051).
 */
@Composable
fun AssistantCard(
    viewModel: ChatViewModel,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AnuTheme.colors
    val asked = state.bubbles.lastOrNull { it.fromUser }?.text
    val answer = state.bubbles.lastOrNull { !it.fromUser }
    val cloud = answer?.tier == Tier.CLOUD
    Column(modifier.fillMaxWidth().clip(AnuShapes.xl).clickable(onClickLabel = "Open Anu", onClick = onOpenApp).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sphere(state.voice.sphere(), 28.dp, level = state.level, errorPulse = state.failures)
            Text(if (state.listening) "Listening…" else "Anu", style = AnuTheme.type.label, color = colors.textMuted)
        }
        val question = if (state.listening) state.heard.ifEmpty { "Say something…" } else asked
        question?.let {
            Text(it, Modifier.padding(top = 12.dp), style = AnuTheme.type.title.copy(fontSize = 20.sp, lineHeight = 26.sp), color = if (state.listening && state.heard.isEmpty()) colors.textFaint else colors.text)
        }
        // The screen is read only when asked, and the card says so while it is (EXP-2, design review D13).
        state.readingApp?.let { app ->
            Text("Reading $app", Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = AnuTheme.type.caption, color = colors.textMuted)
        }
        if (state.screenOffered && state.readingApp == null && !state.busy) {
            AnuPill(
                if (state.useScreen) "Will use this screen" else "Use this screen",
                onClick = viewModel::toggleUseScreen,
                modifier = Modifier.padding(top = 10.dp).semantics { selected = state.useScreen },
            )
        }
        if (!state.listening && answer != null) {
            Column(Modifier.padding(top = 8.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = answer.text.ifEmpty { answer.note ?: if (answer.streaming) "…" else "" }
                Text(markdown(shown), style = AnuTheme.type.body, color = if (answer.failed) colors.errorText else colors.text)
                answer.actions.forEach { ReceiptRow(it) }
                LinkButtons(answer.links)
            }
        }
        state.voiceProblem?.let { Text(it, Modifier.padding(top = 8.dp), style = AnuTheme.type.caption, color = colors.textMuted) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PrivacyDot(away = cloud, size = 6.dp)
            Spacer(Modifier.width(6.dp))
            val readScreen = state.bubbles.lastOrNull { it.fromUser }?.screenApp
            Text(
                when {
                    cloud -> "Gemini (cloud)"
                    readScreen != null -> "On device · read your screen in $readScreen"
                    else -> "On device · reads the screen only when you ask"
                },
                Modifier.weight(1f),
                style = AnuTheme.type.caption,
                color = colors.textMuted,
            )
            val (icon, label) = if (state.listening) AnuIcons.Stop to "Stop" else AnuIcons.Mic to "Talk"
            Box(
                Modifier.size(38.dp).clip(AnuShapes.full).background(colors.primary)
                    .clickable(onClickLabel = label) { if (micGranted) viewModel.toggleListening() else onRequestMic() },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = label, Modifier.size(18.dp), tint = colors.onPrimary) }
        }
    }
    ConversationSheets(state, viewModel::approve, viewModel::deny, viewModel::revise, viewModel::approveCloud, viewModel::declineCloud)
}
