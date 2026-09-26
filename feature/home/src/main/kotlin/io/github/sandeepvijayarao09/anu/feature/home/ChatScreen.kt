package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ChatScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatContent(
        state = state,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onRetryWithPro = viewModel::retryWithPro,
        onApproveCloud = viewModel::approveCloud,
        onDeclineCloud = viewModel::declineCloud,
        onApprove = viewModel::approve,
        onDeny = viewModel::deny,
        modifier = modifier,
    )
}

@Composable
internal fun ChatContent(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetryWithPro: () -> Unit,
    onApproveCloud: () -> Unit,
    onDeclineCloud: () -> Unit,
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.bubbles.size, state.bubbles.lastOrNull()?.text?.length) {
        if (state.bubbles.isNotEmpty()) list.animateScrollToItem(state.bubbles.lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        if (state.bubbles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Ask Anu anything. It answers on this phone when it can, and asks before using the cloud.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(state.bubbles, key = { it.id }) { bubble ->
                    val isLastAnswer = bubble == state.bubbles.last() && !bubble.fromUser
                    BubbleRow(bubble, showRetry = isLastAnswer && !state.busy && bubble.tier == Tier.BASE && !bubble.failed, onRetryWithPro)
                }
            }
        }
        if (state.trimmed) {
            // Says why Anu may not recall the start of a long chat, and where the lasting part went.
            Text(
                "Long chat: Anu reads the latest messages here. What it learned earlier is in Memory.",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Anu") },
                maxLines = 5,
            )
            if (state.busy) {
                TextButton(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = { onSend(input); input = "" }, enabled = input.isNotBlank()) { Text("Send") }
            }
        }
    }

    state.approval?.let { request ->
        // The exact payload, re-encoded from validated input by the Tool Broker, never the model's own paraphrase (I2).
        AlertDialog(
            onDismissRequest = {}, // a decision is required, and tapping outside is not one
            title = { Text("Approve this?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.summary, style = MaterialTheme.typography.bodyLarge)
                    Text(request.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        payloadLines(request.payload).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { Button(onClick = { onApprove(request.id) }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { onDeny(request.id) }) { Text("Don't do it") } },
        )
    }

    if (state.consentFor != null) {
        AlertDialog(
            onDismissRequest = onDeclineCloud,
            title = { Text("Use Gemini for this?") },
            text = {
                Text(
                    "This needs the cloud model. Your message goes to Google with email addresses, phone and card " +
                        "numbers replaced, and the send is recorded in the Privacy Ledger in Settings.",
                )
            },
            confirmButton = { Button(onClick = onApproveCloud) { Text("Send to Gemini") } },
            dismissButton = { TextButton(onClick = onDeclineCloud) { Text("Keep on this phone") } },
        )
    }
}

@Composable
private fun BubbleRow(bubble: Bubble, showRetry: Boolean, onRetryWithPro: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (bubble.fromUser) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                bubble.fromUser -> MaterialTheme.colorScheme.primaryContainer
                bubble.failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                val shown = bubble.text.ifEmpty { if (bubble.streaming) "…" else "" }
                // Only model answers are formatted; the user's own words are shown exactly as typed.
                if (bubble.fromUser) {
                    Text(shown, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(markdown(shown), style = MaterialTheme.typography.bodyLarge)
                }
                bubble.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
                if (bubble.actions.isNotEmpty()) {
                    Text(
                        bubble.actions.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (!bubble.fromUser) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                bubble.tier?.let {
                    Text(it.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showRetry) TextButton(onClick = onRetryWithPro) { Text("Retry with Pro") }
            }
        }
    }
}

/** One line per field, so the user sees every value that will be used. */
internal fun payloadLines(payload: JsonObject): List<String> = payload.filterValues { it !is JsonNull }.map { (key, value) ->
    val shown = when (value) {
        is JsonArray -> value.joinToString { (it as? JsonPrimitive)?.content ?: it.toString() }.ifEmpty { "none" }
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
    "$key: $shown"
}

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    MaterialTheme {
        ChatContent(
            state = ChatUiState(
                bubbles = listOf(
                    Bubble(0, fromUser = true, text = "What's a good way to say no to a meeting?"),
                    Bubble(1, fromUser = false, text = "Thank them, give a short reason, and offer an alternative.", tier = Tier.BASE),
                ),
            ),
            onSend = {}, onStop = {}, onRetryWithPro = {}, onApproveCloud = {}, onDeclineCloud = {},
        )
    }
}
