package io.github.sandeepvijayarao09.anu.feature.home

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onRequestMic: () -> Unit,
    sentToday: Int,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    awayPulse: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AnuTheme.colors
    var input by rememberSaveable { mutableStateOf("") }
    val typing = remember { FocusRequester() }
    val talk = { if (micGranted) viewModel.toggleListening() else onRequestMic() }
    val list = rememberLazyListState()
    // A hairline under the app bar once the thread scrolls beneath it.
    val scrolled by remember { derivedStateOf { list.canScrollBackward } }

    Column(modifier.fillMaxSize().background(colors.background).imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.bubbles.isNotEmpty() || state.listening) {
                Sphere(state.voice.sphere(), 26.dp, level = state.level, awayPulse = awayPulse)
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
                else -> Thread(state, list, onRetryWithPro = viewModel::retryWithPro)
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
            VoiceNote(state, onGetSpeech = viewModel::downloadLanguagePack)
            Composer(
                value = input,
                onValueChange = { input = it },
                busy = state.busy,
                onSend = { viewModel.send(input); input = "" },
                onStop = viewModel::stop,
                onTalk = talk,
                focus = typing,
            )
        }
    }
    ConversationSheets(state, viewModel::approve, viewModel::deny, viewModel::approveCloud, viewModel::declineCloud)
}

@Composable
private fun Welcome(state: ChatUiState, awayPulse: Int, onSuggestion: (String) -> Unit, onTalk: () -> Unit) {
    val colors = AnuTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Sphere(state.voice.sphere(), 168.dp, level = state.level, awayPulse = awayPulse, shadow = true, onClick = onTalk)
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
        Sphere(SphereState.LISTENING, 200.dp, level = state.level, shadow = true)
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
private fun Thread(state: ChatUiState, list: LazyListState, onRetryWithPro: () -> Unit) {
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
                YourTurn(bubble.text)
            } else {
                val last = bubble == state.bubbles.last()
                AnuTurn(bubble, showRetry = last && !state.busy && bubble.tier == Tier.BASE && !bubble.failed, onRetryWithPro)
            }
        }
    }
}

@Composable
private fun YourTurn(text: String) {
    val colors = AnuTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp)).background(colors.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
            style = AnuTheme.type.body,
            color = colors.text,
        )
    }
}

@Composable
private fun AnuTurn(bubble: Bubble, showRetry: Boolean, onRetryWithPro: () -> Unit) {
    val colors = AnuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            bubble.text.isNotEmpty() -> Text(markdown(bubble.text), style = AnuTheme.type.body, color = if (bubble.failed) colors.errorText else colors.text)
            bubble.streaming -> Text(bubble.note ?: "…", style = AnuTheme.type.callout, color = colors.textMuted)
        }
        if (bubble.text.isNotEmpty() && bubble.note != null) Text(bubble.note, style = AnuTheme.type.caption, color = colors.textMuted)
        bubble.actions.forEach { ReceiptRow(it) }
        LinkButtons(bubble.links)
        if (!bubble.streaming && bubble.text.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                bubble.tier?.let { tier ->
                    PrivacyDot(away = tier == Tier.CLOUD, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(tier.label, style = AnuTheme.type.caption, color = colors.textMuted)
                }
                if (showRetry) {
                    Spacer(Modifier.width(8.dp))
                    AnuTextButton("Retry with a stronger model", onClick = onRetryWithPro)
                }
            }
        }
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
    else -> AnuIcons.Sparkle
}

/** Links a tool offered, such as "Finish at shoes.example.com". Nothing opens until the user taps (ADR-047). */
@Composable
internal fun LinkButtons(links: List<LinkAction>) {
    val uri = LocalUriHandler.current
    links.forEach { link -> AnuTextButton(link.label, onClick = { runCatching { uri.openUri(link.url) } }) }
}

/** Why voice didn't work, where it can't be missed, with the fix when there is one. */
@Composable
private fun VoiceNote(state: ChatUiState, onGetSpeech: () -> Unit) {
    val note = state.voiceNote ?: return
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
) {
    val colors = AnuTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp).heightIn(min = 52.dp).anuCard(AnuShapes.xl).padding(start = 18.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            value.isBlank() -> Triple(AnuIcons.Mic, "Talk", onTalk)
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
            Sphere(state.voice.sphere(), 28.dp, level = state.level)
            Text(if (state.listening) "Listening…" else "Anu", style = AnuTheme.type.label, color = colors.textMuted)
        }
        val question = if (state.listening) state.heard.ifEmpty { "Say something…" } else asked
        question?.let {
            Text(it, Modifier.padding(top = 12.dp), style = AnuTheme.type.title.copy(fontSize = 20.sp, lineHeight = 26.sp), color = if (state.listening && state.heard.isEmpty()) colors.textFaint else colors.text)
        }
        if (!state.listening && answer != null) {
            Column(Modifier.padding(top = 8.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = answer.text.ifEmpty { answer.note ?: if (answer.streaming) "…" else "" }
                Text(markdown(shown), style = AnuTheme.type.body, color = if (answer.failed) colors.errorText else colors.text)
                answer.actions.forEach { ReceiptRow(it) }
                LinkButtons(answer.links)
            }
        }
        state.voiceNote?.let { Text(it, Modifier.padding(top = 8.dp), style = AnuTheme.type.caption, color = colors.textMuted) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PrivacyDot(away = cloud, size = 6.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (cloud) "Gemini (cloud) · not reading your screen" else "On device · not reading your screen",
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
    ConversationSheets(state, viewModel::approve, viewModel::deny, viewModel::approveCloud, viewModel::declineCloud)
}
