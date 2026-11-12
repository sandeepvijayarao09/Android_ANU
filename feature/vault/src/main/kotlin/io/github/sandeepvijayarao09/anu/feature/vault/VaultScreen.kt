package io.github.sandeepvijayarao09.anu.feature.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuDialog
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.DetailPanel
import io.github.sandeepvijayarao09.anu.core.ui.DetailRow
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.Notice
import io.github.sandeepvijayarao09.anu.core.ui.SheetNote
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * The Vault page (§11, ADR-135, DESIGN.md "Vault"), from Settings → Privacy: each saved key with the only sites it may
 * go to. Tapping one opens its sheet: where it may go, which parts of Anu may use it, how it unlocks, and Show, which
 * always asks for a fingerprint or the screen lock first, and Delete, which asks first. "Vault activity" stays a row.
 */
@Composable
fun VaultScreen(viewModel: VaultViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onOpenActivity: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    AnuPage(
        "Vault",
        sentToday,
        onOpenPrivacy,
        back = "Settings",
        onBack = onBack,
        subtitle = "Keys Anu uses for you, encrypted on this phone. Models never see them.",
    ) {
        state.error?.let { text -> item(key = "error") { Notice(text, viewModel::dismissError) } }
        if (state.loaded && state.items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing saved yet. A key you add for Gemini, your Mac or a service is kept here.",
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                    style = AnuTheme.type.callout,
                    color = AnuTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (state.items.isNotEmpty()) {
            item(key = "items") {
                Column {
                    Group {
                        state.items.forEachIndexed { index, item ->
                            if (index > 0) GroupDivider()
                            GroupRow(
                                title = item.label,
                                subtitle = where(item),
                                value = item.lastUsedAtMillis?.let { "Used ${shortWhen(it)}" },
                                chevron = true,
                                onClick = { open = item.id },
                            )
                        }
                    }
                    GroupFooter("Anu attaches a key only when it talks to that key's site, and never puts it in a message or a model's view.")
                }
            }
        }
        item(key = "activity") {
            Group(Modifier.padding(top = 8.dp)) { GroupRow("Vault activity", chevron = true, onClick = onOpenActivity) }
        }
    }
    open?.let { id ->
        val item = state.items.find { it.id == id }
        // A deleted item is gone: close its sheet.
        LaunchedEffect(item == null) { if (item == null) open = null }
        if (item != null) {
            ItemSheet(item, state, viewModel, onDismiss = { viewModel.hide(); viewModel.dismissError(); open = null })
        }
    }
}

@Composable
private fun ItemSheet(item: VaultEntry, state: VaultUiState, viewModel: VaultViewModel, onDismiss: () -> Unit) {
    val colors = AnuTheme.colors
    val context = LocalContext.current
    var deleting by remember(item.id) { mutableStateOf(false) }
    val shown = state.shownId == item.id && state.shownValue != null
    val unlock = { biometric(context, item.label, onPassed = { viewModel.reveal(item.id) }, onFailed = { why -> viewModel.unlockDismissed(why) }) }
    // The vault still needed the user after the prompt (its session had ended): ask once more.
    LaunchedEffect(state.unlockAgainFor) { if (state.unlockAgainFor == item.id) unlock() }
    AnuSheet(onDismiss) { _ ->
        // No screenshots or recent-apps picture while a value is on screen.
        if (shown) SecureWindow()
        SheetTitle(item.label, overline = item.kind)
        WhereLine(leaves = false, text = "Kept on this phone, encrypted", modifier = Modifier.padding(top = 10.dp))
        DetailPanel(Modifier.padding(top = 16.dp)) {
            DetailRow("Goes to", item.hosts.joinToString().ifEmpty { "Nowhere: it never leaves this phone" }, first = true)
            if (item.service.isNotBlank() && item.service !in item.hosts) DetailRow("For", item.service)
            DetailRow("Used by", item.usedBy.joinToString().ifEmpty { "Only you" })
            DetailRow("Unlocks", if (item.whileUnlocked) "While the phone is unlocked" else "After your fingerprint or screen lock")
            DetailRow("Saved", longDate(item.savedAtMillis))
            DetailRow("Last used", item.lastUsedAtMillis?.let(::longDate) ?: "Not yet")
        }
        if (shown) {
            SelectionContainer {
                Text(state.shownValue.orEmpty(), Modifier.padding(top = 16.dp), style = AnuTheme.type.mono, color = colors.text)
            }
        } else {
            Text("••••••••••••", Modifier.padding(top = 16.dp), style = AnuTheme.type.mono, color = colors.textMuted)
        }
        // Why Show couldn't ask, such as no screen lock, said here where the user is looking.
        state.error?.let { Text(it, Modifier.padding(top = 12.dp), style = AnuTheme.type.callout, color = colors.errorText) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnuButton(if (shown) "Hide" else "Show", onClick = { if (shown) viewModel.hide() else unlock() }, Modifier.weight(1f), secondary = true)
            AnuButton("Delete", onClick = { deleting = true }, Modifier.weight(1f), secondary = true, destructive = true)
        }
        SheetNote("Show asks for your fingerprint or screen lock first. The value stays on screen until you close this.")
    }
    if (deleting) {
        AnuDialog(
            title = "Delete “${item.label}”?",
            text = "Anu forgets it, and anything that uses it stops working until you add it again. This can't be undone.",
            confirm = "Delete",
            onConfirm = { deleting = false; viewModel.delete(item.id) },
            dismiss = "Keep",
            onDismiss = { deleting = false },
            destructive = true,
        )
    }
}

/** Keeps the window this is in, and the activity's, out of screenshots and the recent-apps picture while shown. */
@Composable
private fun SecureWindow() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val windows = listOfNotNull((view.parent as? DialogWindowProvider)?.window, context.activity()?.window)
        windows.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        onDispose { windows.forEach { it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }
}

/**
 * The system's prompt (§11): a strong biometric or the screen lock, whatever the item's level, since Show always asks.
 * The framework prompt needs no Fragment, so it works from the app's ComponentActivity.
 */
private fun biometric(context: Context, label: String, onPassed: () -> Unit, onFailed: (why: String?) -> Unit) {
    // With no fingerprint and no screen lock, nothing can prove it's the owner: say how to show values.
    val canAsk = context.getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
    if (canAsk != BiometricManager.BIOMETRIC_SUCCESS) return onFailed(NO_LOCK)
    val prompt = BiometricPrompt.Builder(context)
        .setTitle("Show “$label”")
        .setSubtitle("Anu shows it only on this screen")
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()
    prompt.authenticate(
        CancellationSignal(),
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onPassed()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                onFailed(if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) NO_LOCK else null)
        },
    )
}

private const val NO_LOCK = "Set a screen lock in Android's settings to show saved values. Anu needs it to know it's you."

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** "Sent only to generativelanguage.googleapis.com", or that it never leaves. */
internal fun where(item: VaultEntry): String = when (item.hosts.size) {
    0 -> "Never sent"
    1 -> "Sent only to ${item.hosts.single()}"
    else -> "Sent only to ${item.hosts.first()} and ${item.hosts.size - 1} more"
}

private fun shortWhen(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(Instant.ofEpochMilli(millis))
    }
}

private fun longDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
