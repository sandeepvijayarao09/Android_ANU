package io.github.sandeepvijayarao09.anu.feature.settings

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuDialog
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextField
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.DetailPanel
import io.github.sandeepvijayarao09.anu.core.ui.DetailRow
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.Notice
import io.github.sandeepvijayarao09.anu.core.ui.SectionHeader
import io.github.sandeepvijayarao09.anu.core.ui.SheetNote
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val BACK = SettingsPage.MAIN.title

/**
 * Services the user adds as MCP servers (ADR-046). Their tools are offered when the user names the
 * service, and anything that changes something there asks first.
 */
@Composable
internal fun ServicesPage(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val servers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        viewModel.loadMcpTools()
        onPauseOrDispose {}
    }
    AnuPage(
        "Services",
        sentToday,
        onOpenPrivacy,
        back = BACK,
        onBack = onBack,
        subtitle = "Tools from MCP servers you add, such as your notes or tasks. Name a service in a request to use it.",
    ) {
        message?.let { text -> item(key = "message") { Notice(text, viewModel::dismissMessage) } }
        item(key = "servers") {
            Column {
                SectionHeader("Servers")
                Group {
                    servers.forEach { server ->
                        GroupRow(
                            server.name,
                            subtitle = serverDetail(server),
                            subtitleColor = if (server.error != null) AnuTheme.colors.errorText else null,
                            chevron = true,
                            onClick = { open = server.id },
                        )
                        GroupDivider()
                    }
                    GroupRow(
                        if (connecting) "Connecting…" else "Add a server",
                        icon = AnuIcons.Plus,
                        titleColor = AnuTheme.colors.accent,
                        enabled = !connecting,
                        onClick = { adding = true },
                    )
                }
                GroupFooter("Anu asks before anything that changes something on a server, and every request to a server is in the Privacy report.")
            }
        }
    }
    if (adding) AddServerSheet(onDismiss = { adding = false }, onAdd = { name, url, token -> viewModel.addMcpServer(name, url, token) })
    open?.let { id ->
        servers.find { it.id == id }?.let { server ->
            ServerSheet(
                server,
                connecting,
                onRetry = { viewModel.refreshMcpServer(id) },
                onRemove = { viewModel.removeMcpServer(id) },
                onDismiss = { open = null },
            )
        }
    }
}

private fun serverDetail(server: McpServerRow): String = when {
    server.error != null -> server.error
    !server.loaded -> "${server.host} · loading its tools…"
    server.tools.isEmpty() -> "${server.host} · no tools"
    server.tools.size == 1 -> "${server.host} · 1 tool"
    else -> "${server.host} · ${server.tools.size} tools"
}

@Composable
private fun ServerSheet(server: McpServerRow, connecting: Boolean, onRetry: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var removing by remember { mutableStateOf(false) }
    AnuSheet(onDismiss) {
        SheetTitle(server.name, overline = "Service")
        WhereLine(leaves = true, text = "Requests go to ${server.host}", modifier = Modifier.padding(top = 10.dp))
        DetailPanel(Modifier.padding(top = 16.dp)) {
            DetailRow("Tools", if (server.loaded) server.tools.joinToString(", ").ifEmpty { "None" } else "Loading…", first = true)
            DetailRow("Token", if (server.hasToken) "In Anu's vault" else "None")
            server.error?.let { DetailRow("Problem", it, valueColor = AnuTheme.colors.errorText) }
        }
        SheetNote("Anu offers this server's tools only when you name it in a request.")
        Spacer(Modifier.height(18.dp))
        if (server.error != null) {
            AnuButton(if (connecting) "Connecting…" else "Try again", onClick = onRetry, Modifier.fillMaxWidth(), enabled = !connecting)
            Spacer(Modifier.height(10.dp))
        }
        AnuButton("Remove", onClick = { removing = true }, Modifier.fillMaxWidth(), secondary = true, destructive = true)
    }
    if (removing) {
        AnuDialog(
            title = "Remove ${server.name}?",
            text = if (server.hasToken) "Anu stops using it, and deletes its token from the vault." else "Anu stops using it.",
            confirm = "Remove",
            onConfirm = { removing = false; onRemove(); onDismiss() },
            dismiss = "Keep",
            onDismiss = { removing = false },
            destructive = true,
        )
    }
}

@Composable
private fun AddServerSheet(onDismiss: () -> Unit, onAdd: (name: String, url: String, token: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    // A token is a secret: plain remember, so it is never written to saved state.
    var token by remember { mutableStateOf("") }
    AnuSheet(onDismiss) { close ->
        SheetTitle("Add a server")
        Text(
            "An MCP server gives Anu tools, such as your notes or tasks.",
            Modifier.padding(top = 8.dp),
            style = AnuTheme.type.callout,
            color = AnuTheme.colors.textMuted,
        )
        AnuTextField(name, { name = it }, Modifier.padding(top = 18.dp), label = "Name", placeholder = "Notes")
        AnuTextField(
            url,
            { url = it },
            Modifier.padding(top = 14.dp),
            label = "Server address",
            placeholder = "https://…/mcp",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        AnuTextField(token, { token = it }, Modifier.padding(top = 14.dp), label = "Token (optional)", secret = true)
        SheetNote("A token goes into Anu's vault. It is sent only to this server, and never shown again.")
        Spacer(Modifier.height(18.dp))
        AnuButton("Add", onClick = { onAdd(name, url, token); token = ""; close() }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && url.isNotBlank())
        Spacer(Modifier.height(10.dp))
        AnuButton("Cancel", onClick = close, Modifier.fillMaxWidth(), secondary = true)
    }
}

/**
 * Stores that speak the Universal Commerce Protocol (ADR-047). Anu searches them and starts checkouts the
 * user approves; the user always reviews and pays on the store's own site.
 */
@Composable
internal fun StoresPage(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    AnuPage(
        "Stores",
        sentToday,
        onOpenPrivacy,
        back = BACK,
        onBack = onBack,
        subtitle = "Stores that support the Universal Commerce Protocol. Anu finds products and starts checkouts you approve.",
    ) {
        message?.let { text -> item(key = "message") { Notice(text, viewModel::dismissMessage) } }
        item(key = "stores") {
            Column {
                SectionHeader("Stores")
                Group {
                    stores.forEach { store ->
                        GroupRow(store.name, subtitle = store.endpointHost.takeIf { it != store.name }, chevron = true, onClick = { open = store.address })
                        GroupDivider()
                    }
                    GroupRow(
                        if (connecting) "Connecting…" else "Add a store",
                        icon = AnuIcons.Plus,
                        titleColor = AnuTheme.colors.accent,
                        enabled = !connecting,
                        onClick = { adding = true },
                    )
                }
                GroupFooter("You always review and pay on the store's own site. Anu never pays.")
            }
        }
    }
    if (adding) AddStoreSheet(onDismiss = { adding = false }, onAdd = { viewModel.addStore(it) })
    open?.let { address ->
        stores.find { it.address == address }?.let { store ->
            StoreSheet(store, onRemove = { viewModel.removeStore(address) }, onDismiss = { open = null })
        }
    }
}

@Composable
private fun StoreSheet(store: StoreRow, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AnuSheet(onDismiss) { close ->
        SheetTitle(store.name, overline = "Store")
        WhereLine(leaves = true, text = "Requests go to ${store.endpointHost}", modifier = Modifier.padding(top = 10.dp))
        val offers = listOfNotNull("search".takeIf { store.canSearch }, "checkout".takeIf { store.canCheckout })
            .joinToString(" and ").replaceFirstChar { it.uppercase() }.ifEmpty { "Nothing Anu can use" }
        DetailPanel(Modifier.padding(top = 16.dp)) {
            DetailRow("Address", store.address, first = true)
            DetailRow("Offers", offers)
        }
        SheetNote("Anu starts a checkout only after you approve it, then you finish on the store's site.")
        Spacer(Modifier.height(18.dp))
        AnuButton("Remove", onClick = { onRemove(); close() }, Modifier.fillMaxWidth(), secondary = true, destructive = true)
    }
}

@Composable
private fun AddStoreSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    AnuSheet(onDismiss) { close ->
        SheetTitle("Add a store")
        AnuTextField(
            address,
            { address = it },
            Modifier.padding(top = 18.dp),
            label = "Store's web address",
            placeholder = "shop.example.com",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        SheetNote("Anu reads the store's UCP profile to see what it offers.")
        Spacer(Modifier.height(18.dp))
        AnuButton("Add", onClick = { onAdd(address); close() }, Modifier.fillMaxWidth(), enabled = address.isNotBlank())
        Spacer(Modifier.height(10.dp))
        AnuButton("Cancel", onClick = close, Modifier.fillMaxWidth(), secondary = true)
    }
}

/** The on-device models (§5): download, progress, and delete. Answers from them never leave the phone. */
@Composable
internal fun ModelsPage(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val rows by viewModel.modelRows.collectAsStateWithLifecycle()
    // Downloads show progress in a notification; they still run if the user declines this.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    AnuPage("Models", sentToday, onOpenPrivacy, back = BACK, onBack = onBack, subtitle = "Gemma runs on this phone, so its answers never leave it.") {
        item(key = "models") {
            Column {
                SectionHeader("On this phone")
                Group {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) GroupDivider()
                        ModelRowView(
                            row,
                            onDownload = {
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                viewModel.download(row.info.id)
                            },
                            onDelete = { viewModel.delete(row.info.id) },
                        )
                    }
                }
                GroupFooter("Downloads use Wi-Fi only. Base is faster; Pro handles harder requests.")
            }
        }
        item(key = "search") { SearchByMeaningGroup(viewModel) }
    }
}

/**
 * Search by meaning (ADR-055): EmbeddingGemma finds facts that bear on a message though they share no word.
 * Its licence is accepted on Hugging Face, so for now it is installed from a computer.
 */
@Composable
private fun SearchByMeaningGroup(viewModel: SettingsViewModel) {
    val installed by viewModel.searchInstalled.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshSearch()
        onPauseOrDispose {}
    }
    val ready = installed ?: return
    Column {
        SectionHeader("Search by meaning")
        Group {
            GroupRow(
                "EmbeddingGemma 300M",
                subtitle = if (ready) "184 MB · finds facts by meaning" else "Not installed",
                value = if (ready) "On device" else null,
                leadingDot = if (ready) false else null,
            )
        }
        GroupFooter(
            if (ready) {
                "Anu finds facts that bear on a message even when they share no words, such as your diet when you plan a dinner. It runs on this phone."
            } else {
                "Its licence is accepted on Hugging Face, so for now it's installed from a computer. Until then, Anu matches names and topics."
            },
        )
    }
}

@Composable
private fun ModelRowView(row: ModelRow, onDownload: () -> Unit, onDelete: () -> Unit) {
    val colors = AnuTheme.colors
    val name = modelName(row.info.id)
    val size = gigabytes(row.info.sizeBytes)
    val about = "${modelTier(row.info.id)} · $size · ${row.info.variant.uppercase()}"
    var confirmDelete by remember { mutableStateOf(false) }
    when (val state = row.state) {
        ModelState.NotInstalled -> GroupRow(name, subtitle = about, trailing = { AnuTextButton("Download", onClick = onDownload) })
        is ModelState.Downloading -> Column {
            val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
            GroupRow(name, subtitle = "Downloading · ${(fraction * 100).roundToInt()}% of $size", trailing = { AnuTextButton("Cancel", onClick = onDelete) })
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                color = colors.accent,
                trackColor = colors.surface2,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        // A model is gigabytes to download again, so deleting one is never a single tap.
        ModelState.Ready -> GroupRow(name, subtitle = "$about · Downloaded", trailing = { AnuTextButton("Delete", onClick = { confirmDelete = true }, color = colors.errorText) })
        is ModelState.Failed -> GroupRow(name, subtitle = state.reason, subtitleColor = colors.errorText, trailing = { AnuTextButton("Retry", onClick = onDownload) })
    }
    if (confirmDelete) {
        AnuDialog(
            title = "Delete $name?",
            text = "This frees $size. Downloading it again takes a while on Wi-Fi.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            dismiss = "Keep",
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

/** The Gemini key (§11): it goes straight into the vault and is never shown again. */
@Composable
internal fun GeminiSheet(hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    // A secret: plain remember, so it is never written to saved state.
    var key by remember { mutableStateOf("") }
    AnuSheet(onDismiss) { close ->
        SheetTitle("Gemini", overline = "Cloud model")
        WhereLine(leaves = true, text = "Requests go to $GEMINI_HOST", modifier = Modifier.padding(top = 10.dp))
        Text(
            "Optional, for requests too hard for this phone. Anu asks before sending anything personal, replaces email " +
                "addresses and phone and card numbers first, and lists every request in the Privacy report.",
            Modifier.padding(top = 12.dp),
            style = AnuTheme.type.callout,
            color = AnuTheme.colors.textMuted,
        )
        if (hasKey) {
            DetailPanel(Modifier.padding(top = 16.dp)) { DetailRow("Key", "In Anu's vault", first = true) }
            Spacer(Modifier.height(18.dp))
            AnuButton("Remove key", onClick = { onRemove(); close() }, Modifier.fillMaxWidth(), secondary = true, destructive = true)
        } else {
            AnuTextField(key, { key = it }, Modifier.padding(top = 18.dp), label = "Gemini API key", secret = true)
            SheetNote("The key goes into Anu's vault and is never shown again. It is only ever sent to $GEMINI_HOST.")
            Spacer(Modifier.height(18.dp))
            AnuButton("Save to vault", onClick = { onSave(key.trim()); key = ""; close() }, Modifier.fillMaxWidth(), enabled = key.isNotBlank())
        }
    }
}

/** What Anu did with the user's apps and services, from the Tool Broker's audit (§7.2). */
@Composable
internal fun ActivityPage(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val actions by viewModel.toolActions.collectAsStateWithLifecycle()
    val stamps = rememberStamps()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    AnuPage(
        "Activity",
        sentToday,
        onOpenPrivacy,
        back = BACK,
        onBack = onBack,
        subtitle = "What Anu did with your calendar, contacts, email, messages, services and stores, and what it asked you to approve.",
    ) {
        if (actions.isEmpty()) item(key = "empty") { EmptyLine("Nothing yet.") }
        byPeriod(actions, today, zone) { Instant.ofEpochMilli(it.atMillis) }.forEach { group ->
            item(key = group.period.name) {
                Column {
                    SectionHeader(group.period.title)
                    Group {
                        group.items.forEachIndexed { index, action ->
                            if (index > 0) GroupDivider()
                            GroupRow(
                                action.summary,
                                subtitle = outcomeLabel(action),
                                trailing = { Stamp(stamps.format(Instant.ofEpochMilli(action.atMillis), group.period), group.period) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Every use of a saved key: which part of Anu used it, and for which site (§11). Never the value. */
@Composable
internal fun VaultPage(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val uses by viewModel.vaultUses.collectAsStateWithLifecycle()
    val stamps = rememberStamps()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    AnuPage(
        "Vault activity",
        sentToday,
        onOpenPrivacy,
        back = BACK,
        onBack = onBack,
        subtitle = "Every time a saved key was used, by which part of Anu, and for which site. Never the key itself.",
    ) {
        if (uses.isEmpty()) item(key = "empty") { EmptyLine("No saved key has been used yet.") }
        byPeriod(uses, today, zone) { it.at }.forEach { group ->
            item(key = group.period.name) {
                Column {
                    SectionHeader(group.period.title)
                    Group {
                        group.items.forEachIndexed { index, use ->
                            if (index > 0) GroupDivider()
                            GroupRow(
                                use.label,
                                subtitle = "Used by ${use.caller} for ${use.host}",
                                trailing = { Stamp(stamps.format(use.at, group.period), group.period) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyLine(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
        style = AnuTheme.type.callout,
        color = AnuTheme.colors.textMuted,
        textAlign = TextAlign.Center,
    )
}

/** When a record happened, on the right of its row. Today's times are exact values, so they're in mono. */
@Composable
internal fun Stamp(text: String, period: Period) {
    Text(
        text,
        style = if (period == Period.TODAY) AnuTheme.type.mono.copy(fontSize = 13.sp, lineHeight = 18.sp) else AnuTheme.type.callout,
        color = AnuTheme.colors.textMuted,
        maxLines = 1,
    )
}

/** Formats a record's time for its period: "10:42" today, "Wed" this week, "Sep 12" before. */
internal class Stamps(twentyFourHour: Boolean, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()) {
    private val time = formatter(if (twentyFourHour) "Hm" else "hm", locale, zone)
    private val weekday = formatter("EEE", locale, zone)
    private val date = formatter("MMMd", locale, zone)

    fun format(at: Instant, period: Period): String = when (period) {
        Period.TODAY -> time.format(at)
        Period.WEEK -> weekday.format(at)
        Period.EARLIER -> date.format(at)
    }

    private fun formatter(skeleton: String, locale: Locale, zone: ZoneId): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).withZone(zone)
}

@Composable
internal fun rememberStamps(): Stamps {
    val context = LocalContext.current
    return remember { Stamps(DateFormat.is24HourFormat(context)) }
}
