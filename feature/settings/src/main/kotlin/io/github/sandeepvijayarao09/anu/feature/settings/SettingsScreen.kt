package io.github.sandeepvijayarao09.anu.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val rows by viewModel.modelRows.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasCloudKey.collectAsStateWithLifecycle()
    val ledger by viewModel.ledgerEntries.collectAsStateWithLifecycle()
    val vaultUses by viewModel.vaultUses.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // Downloads show progress in a notification; they still run if the user declines this.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("On-device models", "Answers from these never leave your phone. Downloads use Wi-Fi only.")
        rows.forEach { row ->
            ModelRowView(
                row = row,
                onDownload = {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    viewModel.download(row.info.id)
                },
                onDelete = { viewModel.delete(row.info.id) },
            )
        }

        HorizontalDivider()
        Section(
            "Cloud · Gemini",
            "Optional. Anu asks before sending anything personal. Your key is kept in Anu's vault and is only " +
                "ever sent to generativelanguage.googleapis.com.",
        )
        if (hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key saved in the vault", Modifier.weight(1f))
                TextButton(onClick = { viewModel.removeCloudKey() }) { Text("Remove") }
            }
        } else {
            var key by remember { mutableStateOf("") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Gemini API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.saveCloudKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save to vault") }
        }
        message?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            }
        }

        HorizontalDivider()
        Section("Vault activity", "Every time a saved key was used, by which part of Anu, and for which site.")
        if (vaultUses.isEmpty()) {
            Text("No saved key has been used yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            vaultUses.forEach { use ->
                Column {
                    Text("${use.label} · ${time.format(use.at)}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "used by ${use.caller} for ${use.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()
        Section("Privacy Ledger", "Everything that has left this phone. It records what was sent, never the content.")
        if (ledger.isEmpty()) {
            Text("Nothing has left this phone.", style = MaterialTheme.typography.bodyMedium)
        } else {
            ledger.asReversed().forEach { LedgerRow(it) }
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelRowView(row: ModelRow, onDownload: () -> Unit, onDelete: () -> Unit) {
    val name = if (row.info.id == BrainId.BASE) "Base · Gemma 4 E2B" else "Pro · Gemma 4 E4B"
    val size = "%.1f GB · %s".format(row.info.sizeBytes / 1e9, row.info.variant)
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        // A model is gigabytes to download again, so deleting one is never a single tap.
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $name?") },
            text = { Text("This frees ${"%.1f".format(row.info.sizeBytes / 1e9)} GB. Downloading it again takes a while on Wi-Fi.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            when (row.state) {
                ModelState.NotInstalled -> Button(onClick = onDownload) { Text("Download") }
                is ModelState.Downloading -> OutlinedButton(onClick = onDelete) { Text("Cancel") }
                ModelState.Ready -> {
                    Text("Ready", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
                is ModelState.Failed -> Button(onClick = onDownload) { Text("Retry") }
            }
        }
        when (val state = row.state) {
            is ModelState.Downloading -> LinearProgressIndicator(
                progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            is ModelState.Failed -> Text(state.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
    }
}

private val time = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun LedgerRow(entry: LedgerEntry) {
    val redacted = entry.redacted.entries.joinToString { (kind, count) -> "$count ${kind.label.lowercase()}" }
    Column {
        Text("${time.format(entry.at)} · ${entry.purpose} → ${entry.destination}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "${entry.characters} characters" + if (redacted.isNotEmpty()) " · replaced: $redacted" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
