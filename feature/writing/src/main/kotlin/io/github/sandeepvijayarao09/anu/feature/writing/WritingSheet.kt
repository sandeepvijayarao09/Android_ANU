package io.github.sandeepvijayarao09.anu.feature.writing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.router.Tier

/**
 * The sheet that opens from the text-selection menu in any app (§13). The user's text changes only
 * when they tap Replace (ADR-042).
 */
@Composable
fun WritingSheet(viewModel: WritingViewModel, onReplace: (String) -> Unit, onCopy: (String) -> Unit, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            Modifier.fillMaxWidth().imePadding()
                // Taps on the sheet itself don't close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
        ) {
            SheetContent(state, viewModel, onReplace, onCopy, onClose)
        }
    }
    if (state.consentNeeded) {
        AlertDialog(
            onDismissRequest = viewModel::declineCloud,
            title = { Text("Use Gemini for this?") },
            text = {
                Text(
                    "This needs the cloud model. The text goes to Google with email addresses, phone and card numbers " +
                        "replaced, and the send is recorded in the Privacy Ledger in Anu's Settings.",
                )
            },
            confirmButton = { Button(onClick = viewModel::approveCloud) { Text("Send to Gemini") } },
            dismissButton = { TextButton(onClick = viewModel::declineCloud) { Text("Keep on phone") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetContent(
    state: WritingUiState,
    viewModel: WritingViewModel,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    var instruction by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Write with Anu", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text(
            state.original,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.tooLong) {
            Text("That's too long to rewrite at once. Select a shorter part.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WritingAction.entries.forEach { action ->
                AssistChip(onClick = { viewModel.run(action) }, label = { Text(action.label) }, enabled = !state.busy)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Or tell Anu what to do") },
                singleLine = true,
            )
            Button(onClick = { viewModel.runCustom(instruction) }, enabled = instruction.isNotBlank() && !state.busy) { Text("Go") }
        }
        if (state.asked != null) Result(state, viewModel, onReplace, onCopy)
    }
}

@Composable
private fun Result(state: WritingUiState, viewModel: WritingViewModel, onReplace: (String) -> Unit, onCopy: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                state.result.ifEmpty { if (state.busy) "…" else "" },
                Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
            )
            val status = listOfNotNull(state.tier?.let(WritingViewModel::label), state.note).joinToString(" · ")
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            state.busy -> TextButton(onClick = viewModel::stop) { Text("Stop") }
            state.done -> {
                if (!state.readOnly) Button(onClick = { onReplace(state.result) }) { Text("Replace") }
                OutlinedButton(onClick = { onCopy(state.result) }) { Text("Copy") }
                if (state.tier == Tier.BASE) TextButton(onClick = viewModel::retryWithPro) { Text("Retry with Pro") }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
