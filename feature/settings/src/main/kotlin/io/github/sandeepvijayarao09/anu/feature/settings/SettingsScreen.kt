package io.github.sandeepvijayarao09.anu.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val rows by viewModel.modelRows.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasCloudKey.collectAsStateWithLifecycle()
    val ledger by viewModel.ledgerEntries.collectAsStateWithLifecycle()
    val vaultUses by viewModel.vaultUses.collectAsStateWithLifecycle()
    val toolActions by viewModel.toolActions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // Downloads show progress in a notification; they still run if the user declines this.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        VoiceSection(viewModel)

        HorizontalDivider()
        ConnectorsSection()
        GmailRow(viewModel)
        MessagingRow(viewModel)
        if (viewModel.smsAvailable) SmsRow()

        if (viewModel.mcpAvailable) {
            HorizontalDivider()
            McpSection(viewModel)
        }
        if (viewModel.shoppingAvailable) {
            HorizontalDivider()
            ShoppingSection(viewModel)
        }
        message?.let { MessageRow(it, viewModel) }

        HorizontalDivider()
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
        message?.let { MessageRow(it, viewModel) }

        HorizontalDivider()
        Section("Activity", "What Anu did with your calendar, contacts, email and messages, including what it asked you to approve.")
        if (toolActions.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            toolActions.forEach { action ->
                Column {
                    Text(action.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${outcomeLabel(action)} · ${time.format(Instant.ofEpochMilli(action.atMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/**
 * Talking to Anu over any app (ADR-044, ADR-045). The assistant is chosen in Android's settings, so
 * this checks again whenever the user comes back.
 */
@Composable
private fun VoiceSection(viewModel: SettingsViewModel) {
    val assistant by viewModel.isAssistant.collectAsStateWithLifecycle()
    val wakeWord by viewModel.wakeWord.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAssistant()
        onPauseOrDispose {}
    }
    // The notification says when Anu is listening, so it is asked for too. Listening works without it.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) viewModel.setWakeWord(true)
    }
    val isAssistant = assistant ?: return
    Section("Voice", "Talk to Anu from any app. Speech is recognized on this phone, never on a server.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Anu as your assistant", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Hold the power button, or swipe up from a bottom corner, to talk to Anu over any app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isAssistant) {
            Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = { viewModel.assistantSettings().firstOrNull { runCatching { context.startActivity(it) }.isSuccess } }) {
                Text("Set up")
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("“Hey Anu”", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (isAssistant) {
                    "Listens for “Hey Anu” while your screen is on. A notification shows while it listens, and it uses more battery."
                } else {
                    "Make Anu your assistant first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = wakeWord && isAssistant,
            enabled = isAssistant,
            onCheckedChange = { on ->
                if (on) permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) else viewModel.setWakeWord(false)
            },
        )
    }
}

@Composable
private fun MessageRow(message: String, viewModel: SettingsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
    }
}

/**
 * Services the user adds as MCP servers (ADR-046). Their tools are offered when the user names the
 * service, and anything that changes something there asks first.
 */
@Composable
private fun McpSection(viewModel: SettingsViewModel) {
    val servers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<McpServerRow?>(null) }
    LifecycleResumeEffect(Unit) {
        viewModel.loadMcpTools()
        onPauseOrDispose {}
    }
    Section(
        "Services (MCP)",
        "Tools from servers you add, such as your notes or tasks. Name a service in a request to use it. " +
            "Anu asks before anything that changes something there, and every request to a server is in the Privacy Ledger.",
    )
    servers.forEach { server ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.bodyLarge)
                val detail = when {
                    server.error != null -> server.error
                    !server.loaded -> "${server.host} · loading its tools…"
                    server.tools.isEmpty() -> "${server.host} · no tools"
                    else -> "${server.host} · ${server.tools.size} tools: ${server.tools.take(3).joinToString()}" + if (server.tools.size > 3) "…" else ""
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (server.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (server.error != null) TextButton(onClick = { viewModel.refreshMcpServer(server.id) }, enabled = !connecting) { Text("Retry") }
            TextButton(onClick = { removing = server }) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = { adding = true }, enabled = !connecting) { Text(if (connecting) "Connecting…" else "Add a server") }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add an MCP server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name, such as Notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("Server address (https://…/mcp)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        token,
                        { token = it },
                        label = { Text("Token (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "A token goes into Anu's vault. It is sent only to this server, and never shown again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { adding = false; viewModel.addMcpServer(name, url, token) }, enabled = name.isNotBlank() && url.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${server.name}?") },
            text = { Text(if (server.hasToken) "Anu stops using it, and deletes its token from the vault." else "Anu stops using it.") },
            confirmButton = { TextButton(onClick = { removing = null; viewModel.removeMcpServer(server.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

/**
 * Stores that speak the Universal Commerce Protocol (ADR-047). Anu searches them and starts checkouts the
 * user approves; the user always reviews and pays on the store's own site.
 */
@Composable
private fun ShoppingSection(viewModel: SettingsViewModel) {
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Section(
        "Shopping (UCP)",
        "Stores that support the Universal Commerce Protocol. Anu finds products and starts checkouts you approve. " +
            "You always review and pay on the store's own site: Anu never pays.",
    )
    stores.forEach { store ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(store.name, style = MaterialTheme.typography.bodyLarge)
                val can = listOfNotNull("search".takeIf { store.canSearch }, "checkout".takeIf { store.canCheckout }).joinToString(" and ")
                Text(
                    "Offers $can · requests go to ${store.endpointHost}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { viewModel.removeStore(store.address) }) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = { adding = true }, enabled = !connecting) { Text(if (connecting) "Connecting…" else "Add a store") }
    if (adding) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a store") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(address, { address = it }, label = { Text("Store's web address") }, placeholder = { Text("shop.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Anu reads the store's UCP profile to see what it offers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = { adding = false; viewModel.addStore(address) }, enabled = address.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

private val CONNECTOR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
    Manifest.permission.READ_CONTACTS,
)

/** Calendar and contacts stay on the phone. Granting access is what connects them (§9). */
@Composable
private fun ConnectorsSection() {
    val context = LocalContext.current
    fun allGranted() = CONNECTOR_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(allGranted()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = allGranted() }
    Section("Connectors", "Your calendar, contacts, email and messages. Anything that sends or invites people asks you first.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Calendar and contacts", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (granted) {
            Text("Connected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = { request.launch(CONNECTOR_PERMISSIONS) }) { Text("Connect") }
        }
    }
}

/** Gmail connects through Google's own consent screen. Anu never sees the password (ADR-029). */
@Composable
private fun GmailRow(viewModel: SettingsViewModel) {
    val connected by viewModel.gmailConnected.collectAsStateWithLifecycle()
    val consent by viewModel.gmailConsent.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.finishGmail(result.data)
    }
    LaunchedEffect(consent) { consent?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Gmail", style = MaterialTheme.typography.bodyLarge)
            Text("Search, read and draft. Sending always asks you first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (connected) {
            true -> Text("Connected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            false -> Button(onClick = viewModel::connectGmail) { Text("Connect") }
            null -> Text("Checking…", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Chat apps reach Anu through their notifications, which Android lets the user switch on in its settings (ADR-031). */
@Composable
private fun MessagingRow(viewModel: SettingsViewModel) {
    val enabled by viewModel.messagingEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The user turns access on in Android's settings, so check again whenever they come back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMessaging()
        onPauseOrDispose {}
    }
    val current = enabled ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Messaging apps", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Reads chats from their notifications, such as Messages and WhatsApp. Replies always ask you first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (current) {
            Text("Connected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = { viewModel.messagingSettings().firstOrNull { runCatching { context.startActivity(it) }.isSuccess } }) {
                Text("Connect")
            }
        }
    }
}

/** The phone's SMS history, read-only. Only the `personal` build has this row (ADR-032). */
@Composable
private fun SmsRow() {
    val context = LocalContext.current
    fun granted() = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = granted() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Text message history", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Searches your SMS on this phone. Anu can't send SMS, and codes are hidden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (allowed) {
            Text("Connected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = { request.launch(Manifest.permission.READ_SMS) }) { Text("Connect") }
        }
    }
}

private fun outcomeLabel(action: ToolAuditEntry): String = when {
    action.outcome == "no answer in time" -> "Not answered in time, so not done"
    action.approved == false -> "You declined"
    action.outcome == "ok" && action.approved == true -> "Approved and done"
    action.outcome == "ok" -> "Done"
    action.outcome == "replayed" -> "Already done, not repeated"
    else -> action.outcome.replaceFirstChar { it.uppercase() }
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
