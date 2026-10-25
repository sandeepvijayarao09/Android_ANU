package io.github.sandeepvijayarao09.anu.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuSwitch
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.Notice
import io.github.sandeepvijayarao09.anu.core.ui.SectionHeader
import io.github.sandeepvijayarao09.anu.core.ui.pushTransition
import io.github.sandeepvijayarao09.anu.core.ui.rememberReducedMotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pages inside Settings (DESIGN.md "Grouped lists"). The Privacy report isn't one of them: it opens
 * over whichever page the privacy indicator was tapped on.
 */
enum class SettingsPage(val title: String) {
    MAIN("Settings"),
    SERVICES("Services"),
    STORES("Stores"),
    MODELS("Models"),
    ACTIVITY("Activity"),
    VAULT("Vault activity"),
    LAB("Prompt lab"),
    SKILLS("Skills"),
}

/**
 * Settings: short groups with values on the right, where every connection shows whether it stays on the
 * phone or talks to the outside. Services, stores, models and the records open as pages; a server,
 * a store, the Gemini key and adding things open as sheets.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    sentToday: Int,
    onOpenPrivacy: () -> Unit,
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    /** The prompt lab (G7, ADR-080), when the phone can run models. */
    lab: LabViewModel? = null,
) {
    val home = rememberLazyListState()
    val reducedMotion = rememberReducedMotion()
    val back = { onPage(SettingsPage.MAIN) }
    BackHandler(enabled = page != SettingsPage.MAIN, onBack = back)
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = { pushTransition(forward = targetState != SettingsPage.MAIN, reducedMotion) },
        label = "settings page",
    ) { shown ->
        when (shown) {
            SettingsPage.MAIN -> SettingsHome(viewModel, sentToday, onOpenPrivacy, onPage, home, hasLab = lab != null)
            SettingsPage.SERVICES -> ServicesPage(viewModel, sentToday, onOpenPrivacy, back)
            SettingsPage.STORES -> StoresPage(viewModel, sentToday, onOpenPrivacy, back)
            SettingsPage.MODELS -> ModelsPage(viewModel, sentToday, onOpenPrivacy, back)
            SettingsPage.ACTIVITY -> ActivityPage(viewModel, sentToday, onOpenPrivacy, back)
            SettingsPage.VAULT -> VaultPage(viewModel, sentToday, onOpenPrivacy, back)
            SettingsPage.SKILLS -> SkillsPage(viewModel, sentToday, onOpenPrivacy, back)
            SettingsPage.LAB -> if (lab != null) LabPage(lab, sentToday, onOpenPrivacy, back, onOpenModels = { onPage(SettingsPage.MODELS) }) else back()
        }
    }
}

@Composable
private fun SettingsHome(
    viewModel: SettingsViewModel,
    sentToday: Int,
    onOpenPrivacy: () -> Unit,
    onPage: (SettingsPage) -> Unit,
    list: LazyListState,
    hasLab: Boolean = false,
) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    AnuPage("Settings", sentToday, onOpenPrivacy, listState = list) {
        message?.let { text -> item(key = "message") { Notice(text, viewModel::dismissMessage) } }
        item(key = "voice") { VoiceGroup(viewModel) }
        item(key = "keyboard") { KeyboardGroup() }
        item(key = "connections") { ConnectionsGroup(viewModel, onPage) }
        item(key = "intelligence") { IntelligenceGroup(viewModel, onPage, hasLab) }
        item(key = "privacy") { PrivacyGroup(viewModel, sentToday, onOpenPrivacy, onPage) }
    }
}

/**
 * Talking to Anu over any app (ADR-044, ADR-045). Android decides the assistant, so this checks again
 * whenever the user comes back. Turning "Hey Anu" on first teaches it the user's voice, unless it knows it
 * already; it's on once that's done (ADR-121).
 */
@Composable
private fun VoiceGroup(viewModel: SettingsViewModel) {
    val assistant by viewModel.isAssistant.collectAsStateWithLifecycle()
    val wakeWord by viewModel.wakeWord.collectAsStateWithLifecycle()
    val wakeVoice by viewModel.wakeVoice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var teaching by rememberSaveable { mutableStateOf(false) }
    var turnOnWhenTaught by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAssistant()
        onPauseOrDispose {}
    }
    // The notification says when Anu is listening, so it is asked for too. Listening works without it.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] != true) return@rememberLauncherForActivityResult
        if (wakeVoice) viewModel.setWakeWord(true) else teaching = true
    }
    // Teaching needs only the microphone.
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) teaching = true
    }
    val isAssistant = assistant ?: return
    val listening = wakeWord && isAssistant
    Column {
        SectionHeader("Voice")
        Group {
            GroupRow(
                "Anu as your assistant",
                value = if (isAssistant) "On" else "Set up",
                chevron = true,
                onClick = { viewModel.assistantSettings().firstOrNull { runCatching { context.startActivity(it) }.isSuccess } },
            )
            GroupDivider()
            GroupRow(
                "“Hey Anu”",
                modifier = Modifier.toggleable(value = listening, enabled = isAssistant, role = Role.Switch) { on ->
                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    if (on) {
                        turnOnWhenTaught = !wakeVoice
                        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    } else {
                        viewModel.setWakeWord(false)
                    }
                },
                subtitle = if (isAssistant) "While the screen is on" else "Make Anu your assistant first",
                enabled = isAssistant,
                trailing = { AnuSwitch(listening, onCheckedChange = null, enabled = isAssistant) },
            )
            if (isAssistant) {
                GroupDivider()
                GroupRow(
                    "Your voice",
                    value = if (wakeVoice) "Learned" else "Set up",
                    chevron = true,
                    onClick = {
                        turnOnWhenTaught = false
                        microphone.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
        GroupFooter(
            "Heard on this phone, never on a server. Once Anu learns your voice, it listens for your own “Hey Anu”, " +
                "and the card then listens for your request. While it listens, a notification shows.",
        )
    }
    if (teaching) {
        WakeVoiceSheet(
            learned = wakeVoice,
            setup = viewModel::teachWakeVoice,
            onDone = { if (turnOnWhenTaught) viewModel.setWakeWord(true) },
            onForget = viewModel::forgetWakeVoice,
            onDismiss = {
                teaching = false
                turnOnWhenTaught = false
            },
        )
    }
}

/**
 * Anu's keyboard (ADR-109). Android lets only the user turn a keyboard on, so this row opens its keyboard settings,
 * then the keyboard picker once it's on. It checks again whenever the user comes back.
 */
@Composable
private fun KeyboardGroup() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(KeyboardStatus.OFF) }
    LifecycleResumeEffect(Unit) {
        status = keyboardStatus(context)
        onPauseOrDispose {}
    }
    Column {
        SectionHeader("Keyboard")
        Group {
            GroupRow(
                "Anu Keyboard",
                value = when (status) {
                    KeyboardStatus.IN_USE -> "In use"
                    KeyboardStatus.ON -> "On"
                    KeyboardStatus.OFF -> "Set up"
                },
                chevron = true,
                onClick = {
                    if (status == KeyboardStatus.OFF) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    } else {
                        context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    }
                },
            )
        }
        GroupFooter("Improve, fix, continue or reply as you type in any app, with the models on this phone. Nothing you type is kept or sent, and password and incognito fields get no AI.")
    }
}

private enum class KeyboardStatus { OFF, ON, IN_USE }

private fun keyboardStatus(context: Context): KeyboardStatus {
    val ours = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.firstOrNull { it.packageName == context.packageName }
        ?: return KeyboardStatus.OFF
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return if (current == ours.id) KeyboardStatus.IN_USE else KeyboardStatus.ON
}

private val CONNECTOR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS,
)

/**
 * Connections (§9). Calendar, contacts, chats and SMS stay on the phone; Gmail, services and stores talk
 * to the outside, so their dot is orange.
 */
@Composable
private fun ConnectionsGroup(viewModel: SettingsViewModel, onPage: (SettingsPage) -> Unit) {
    val messaging by viewModel.messagingEnabled.collectAsStateWithLifecycle()
    // Notification access is granted in Android's settings, so check again whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMessaging()
        onPauseOrDispose {}
    }
    Column {
        SectionHeader("Connections")
        Group {
            PermissionRow("Calendar and contacts", CONNECTOR_PERMISSIONS)
            messaging?.let { enabled ->
                GroupDivider()
                MessagingRow(enabled, viewModel)
            }
            if (viewModel.smsAvailable) {
                GroupDivider()
                PermissionRow("Text message history", arrayOf(Manifest.permission.READ_SMS))
            }
            GroupDivider()
            GmailRow(viewModel)
            if (viewModel.mcpAvailable) {
                val servers by viewModel.mcpServers.collectAsStateWithLifecycle()
                GroupDivider()
                GroupRow(
                    "Services",
                    subtitle = servers.joinToString { it.name }.ifEmpty { null },
                    value = if (servers.isEmpty()) "None" else "${servers.size}",
                    leadingDot = if (servers.isEmpty()) null else true,
                    valueDescription = outside(servers.size, "service"),
                    chevron = true,
                    onClick = { onPage(SettingsPage.SERVICES) },
                )
            }
            if (viewModel.shoppingAvailable) {
                val stores by viewModel.stores.collectAsStateWithLifecycle()
                GroupDivider()
                GroupRow(
                    "Stores",
                    subtitle = stores.joinToString { it.endpointHost }.ifEmpty { null },
                    value = if (stores.isEmpty()) "None" else "${stores.size}",
                    leadingDot = if (stores.isEmpty()) null else true,
                    valueDescription = outside(stores.size, "store"),
                    chevron = true,
                    onClick = { onPage(SettingsPage.STORES) },
                )
            }
        }
        GroupFooter(
            "Anything that sends a message, email or invite asks you first. Gmail, services and stores are reached " +
                "over the internet, and every request to them is in the Privacy report.",
        )
    }
}

/**
 * A connection that is an Android permission, such as calendar and contacts, or SMS in the `personal`
 * build (ADR-032). Once granted, the row opens Android's page for Anu, where it can be taken back.
 */
@Composable
private fun PermissionRow(title: String, permissions: Array<String>) {
    val context = LocalContext.current
    fun granted() = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    LifecycleResumeEffect(Unit) {
        allowed = granted()
        onPauseOrDispose {}
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted() }
    if (allowed) {
        GroupRow(title, value = "On device", leadingDot = false, chevron = true, onClick = { context.openAppSettings() })
    } else {
        GroupRow(title, value = "Connect", chevron = true, onClick = { request.launch(permissions) })
    }
}

/** Chat apps reach Anu through their notifications, which only Android's settings can allow (ADR-031). */
@Composable
private fun MessagingRow(enabled: Boolean, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    GroupRow(
        "Messaging apps",
        value = if (enabled) "On device" else "Connect",
        leadingDot = if (enabled) false else null,
        chevron = true,
        onClick = { viewModel.messagingSettings().firstOrNull { runCatching { context.startActivity(it) }.isSuccess } },
    )
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
    when (connected) {
        true -> GroupRow("Gmail", value = "Connected", leadingDot = true, valueDescription = "Connected, reached over the internet")
        false -> GroupRow("Gmail", value = "Connect", chevron = true, onClick = viewModel::connectGmail)
        null -> GroupRow("Gmail", value = "Checking…")
    }
}

/** The models on the phone, and Gemini in the cloud, which is used only with consent (§5, §12.2). */
@Composable
private fun IntelligenceGroup(viewModel: SettingsViewModel, onPage: (SettingsPage) -> Unit, hasLab: Boolean = false) {
    val rows by viewModel.modelRows.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasCloudKey.collectAsStateWithLifecycle()
    val thinking by viewModel.thinking.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var gemini by rememberSaveable { mutableStateOf(false) }
    val mac by viewModel.macPaired.collectAsStateWithLifecycle()
    var macSheet by rememberSaveable { mutableStateOf(false) }
    Column {
        SectionHeader("Intelligence")
        Group {
            GroupRow("Models", value = modelsSummary(rows), chevron = true, onClick = { onPage(SettingsPage.MODELS) })
            if (viewModel.skillsAvailable) {
                val skills by viewModel.skills.collectAsStateWithLifecycle()
                GroupDivider()
                // AI Edge Gallery's Agent Skills (ADR-120).
                GroupRow("Skills", value = "${skills.count { it.enabled }} on", chevron = true, onClick = { onPage(SettingsPage.SKILLS) })
            }
            GroupDivider()
            GroupRow(
                "Gemini",
                value = if (hasKey) "Key saved" else "Off",
                leadingDot = if (hasKey) true else null,
                valueDescription = if (hasKey) "Key saved, reached over the internet" else null,
                chevron = true,
                onClick = { gemini = true },
            )
            if (viewModel.canPairMac) {
                GroupDivider()
                // Gemma on the user's own Mac, in Gemini's place (ADR-113).
                GroupRow(
                    "Your Mac",
                    value = if (mac != null) "Paired" else "Off",
                    leadingDot = if (mac != null) true else null,
                    valueDescription = if (mac != null) "Paired, reached over your Wi-Fi" else null,
                    chevron = true,
                    onClick = { macSheet = true },
                )
            }
            if (viewModel.canThink) {
                GroupDivider()
                // Reasoning first helps on hard questions and costs seconds before the answer (G4, ADR-077).
                GroupRow(
                    "Think before answering",
                    modifier = Modifier.toggleable(value = thinking, role = Role.Switch) { on ->
                        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        viewModel.setThinking(on)
                    },
                    subtitle = "Better on hard questions; answers start later",
                    trailing = { AnuSwitch(thinking, onCheckedChange = null) },
                )
            }
            if (hasLab) {
                GroupDivider()
                // Try prompts and sampling on the phone's models, apart from the assistant (G7, ADR-080).
                GroupRow("Prompt lab", chevron = true, onClick = { onPage(SettingsPage.LAB) })
            }
        }
        GroupFooter(
            if (mac != null) "Answers from the models on this phone never leave it. Anu asks before anything personal goes to your Mac."
            else "Answers from the models on this phone never leave it. Anu asks before anything personal goes to Gemini.",
        )
    }
    if (gemini) {
        GeminiSheet(
            hasKey = hasKey,
            onSave = { viewModel.saveCloudKey(it) },
            onRemove = { viewModel.removeCloudKey() },
            onDismiss = { gemini = false },
        )
    }
    if (macSheet) {
        MacSheet(
            paired = mac,
            onPair = { viewModel.pairMac(it) },
            onUnpair = { viewModel.unpairMac() },
            onDismiss = { macSheet = false },
        )
    }
}

/** The records Anu keeps on the phone: what left it, what Anu did, and when a saved key was used. */
@Composable
private fun PrivacyGroup(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onPage: (SettingsPage) -> Unit) {
    val actions by viewModel.toolActions.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val actionsToday = actions.count { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() == today }
    Column {
        SectionHeader("Privacy")
        Group {
            GroupRow(
                "Privacy report",
                value = if (sentToday > 0) "$sentToday sent today" else "Nothing sent today",
                leadingDot = sentToday > 0,
                chevron = true,
                onClick = onOpenPrivacy,
            )
            GroupDivider()
            GroupRow("Activity", value = if (actionsToday > 0) "$actionsToday today" else null, chevron = true, onClick = { onPage(SettingsPage.ACTIVITY) })
            GroupDivider()
            GroupRow("Vault activity", chevron = true, onClick = { onPage(SettingsPage.VAULT) })
        }
        GroupFooter("These records stay on this phone. They say what happened and where, never the content.")
    }
}

/** What TalkBack says for a count of things that talk to the outside, since the orange dot can't be heard. */
private fun outside(count: Int, thing: String): String? = when (count) {
    0 -> null
    1 -> "1 $thing, reached over the internet"
    else -> "$count ${thing}s, reached over the internet"
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
