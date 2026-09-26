package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Talk page (ADR-043): the orb in the middle, live captions under it, and the whole conversation,
 * with typing, a tap away. [compact] is the assistant overlay's smaller version (ADR-044).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(
    viewModel: ChatViewModel,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var chatOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
        Text(status(state), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Why voice didn't work, where it can't be missed.
        state.voiceNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (state.offerLanguagePack) TextButton(onClick = viewModel::downloadLanguagePack) { Text("Get on-device speech") }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Orb(state.voice, state.level, Modifier.size(if (compact) 200.dp else 300.dp)) {
                if (micGranted) viewModel.toggleListening() else onRequestMic()
            }
        }
        Captions(state, Modifier.fillMaxWidth().heightIn(min = 72.dp, max = if (compact) 160.dp else 220.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = { chatOpen = true }) { Text(if (compact) "Type" else "Type or see the chat") }
            TextButton(onClick = viewModel::toggleMute) { Text(if (state.muted) "Unmute" else "Mute") }
            if (state.busy) TextButton(onClick = viewModel::stop) { Text("Stop") }
        }
    }

    ConversationDialogs(state, viewModel::approve, viewModel::deny, viewModel::approveCloud, viewModel::declineCloud)

    if (chatOpen) {
        // Fully open, so the message field shows at once: half open, it was below the fold.
        ModalBottomSheet(onDismissRequest = { chatOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ChatContent(
                state = state,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onRetryWithPro = viewModel::retryWithPro,
                onApproveCloud = viewModel::approveCloud,
                onDeclineCloud = viewModel::declineCloud,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                modifier = Modifier.fillMaxHeight(0.9f),
                showDialogs = false,
            )
        }
    }
}

private fun status(state: ChatUiState): String = when (state.voice) {
    VoicePhase.LISTENING -> "Listening…"
    VoicePhase.THINKING -> "Thinking…"
    VoicePhase.SPEAKING -> "Speaking…"
    VoicePhase.IDLE -> if (state.voiceAvailable) "Tap the orb and speak" else "Tap Type to talk to Anu"
}

/** What the user is saying while Anu listens; otherwise the last request and Anu's answer. */
@Composable
private fun Captions(state: ChatUiState, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.listening) {
            Text(
                state.heard.ifEmpty { "Say something…" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        val answer = state.bubbles.lastOrNull { !it.fromUser }
        val asked = state.bubbles.lastOrNull { it.fromUser }
        asked?.let {
            Text(it.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        answer?.let { bubble ->
            val shown = bubble.text.ifEmpty { if (bubble.streaming) "…" else "" }
            Text(
                markdown(shown),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = if (bubble.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            bubble.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (bubble.actions.isNotEmpty()) {
                Text(bubble.actions.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            LinkButtons(bubble.links)
            bubble.tier?.let { Text(it.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (state.trimmed) {
            Text(
                "Long chat: Anu reads the latest messages. What it learned earlier is in Memory.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
