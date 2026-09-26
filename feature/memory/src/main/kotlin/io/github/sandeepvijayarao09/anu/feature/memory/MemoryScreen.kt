package io.github.sandeepvijayarao09.anu.feature.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.memory.EventSource
import io.github.sandeepvijayarao09.anu.core.memory.Fact
import io.github.sandeepvijayarao09.anu.core.memory.FactOrigin
import io.github.sandeepvijayarao09.anu.core.memory.MemoryEvent
import io.github.sandeepvijayarao09.anu.core.memory.MemoryPipeline
import io.github.sandeepvijayarao09.anu.core.memory.MemoryState
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MemoryScreen(viewModel: MemoryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Memory", style = MaterialTheme.typography.headlineSmall)
        Text(
            "What Anu has learned from you. It stays on this phone. Tap anything to see why Anu thinks it, " +
                "and to confirm, correct or delete it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LearningRow(state, viewModel)
        message?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            }
        }
        if (state.facts.isEmpty()) {
            Text(
                "Nothing yet. Tell Anu about yourself as you chat, or say \"Remember that…\".",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ProfileSection.entries.forEach { section ->
            val facts = state.facts.filter { it.section == section }.sortedWith(MemoryPipeline.IMPORTANCE)
            if (facts.isNotEmpty()) {
                HorizontalDivider()
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                facts.forEach { FactRow(it, state.sources, viewModel) }
            }
        }
    }
}

/** Learning happens after the user leaves the app, or while charging (ADR-037). This offers it now. */
@Composable
private fun LearningRow(state: MemoryState, viewModel: MemoryViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = when {
            state.learning -> "Learning…"
            state.pending == 0 -> "Up to date."
            state.pending == 1 -> "1 message to learn from. Anu learns after you leave the app, or while charging."
            else -> "${state.pending} messages to learn from. Anu learns after you leave the app, or while charging."
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (state.learning) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (state.pending > 0) {
            TextButton(onClick = viewModel::learnNow) { Text("Learn now") }
        }
    }
}

@Composable
private fun FactRow(fact: Fact, sources: Map<Long, MemoryEvent>, viewModel: MemoryViewModel) {
    var expanded by remember(fact.id) { mutableStateOf(false) }
    var editing by remember(fact.id) { mutableStateOf(false) }
    var deleting by remember(fact.id) { mutableStateOf(false) }
    var forgetting by remember(fact.id) { mutableStateOf<MemoryEvent?>(null) }

    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(fact.text, style = MaterialTheme.typography.bodyLarge)
        Text(origin(fact), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) {
            fact.sources.mapNotNull(sources::get).forEach { event ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${source(event)} · ${DAY.format(event.at)}\n“${event.content.take(160)}”",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (event.source == EventSource.CHAT) TextButton(onClick = { forgetting = event }) { Text("Forget") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fact.origin == FactOrigin.INFERRED) OutlinedButton(onClick = { viewModel.confirm(fact.id) }) { Text("That's right") }
                OutlinedButton(onClick = { editing = true }) { Text("Correct") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
            }
        }
    }

    if (editing) {
        var value by remember(fact.id) { mutableStateOf(fact.value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Correct \"${fact.predicate.replace('_', ' ')}\"") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { editing = false; viewModel.correct(fact.id, value) }, enabled = value.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${fact.text}\"?") },
            text = { Text("Anu forgets it and won't learn it again.") },
            confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete(fact.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
    }
    forgetting?.let { event ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget this message?") },
            text = { Text("Anu forgets the message, and anything it learned only from it. The chat itself isn't changed.") },
            confirmButton = { TextButton(onClick = { forgetting = null; viewModel.forgetSource(event.id) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Keep") } },
        )
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun origin(fact: Fact): String = buildString {
    append(if (fact.origin == FactOrigin.USER_STATED) "You said so" else "Learned from what you wrote")
    if (fact.origin == FactOrigin.INFERRED && fact.sources.size > 1) append(" · ${fact.sources.size} times")
    if (fact.sensitive) append(" · sensitive")
}

private fun source(event: MemoryEvent): String = when (event.source) {
    EventSource.CHAT -> "Your message"
    EventSource.STATEMENT -> "You asked Anu to remember"
    EventSource.EDIT -> "Your edit"
}
