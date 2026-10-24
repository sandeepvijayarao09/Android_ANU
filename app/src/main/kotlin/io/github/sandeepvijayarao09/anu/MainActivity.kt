package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.feature.settings.LabViewModel
import io.github.sandeepvijayarao09.anu.feature.home.VOICE_NOTE_SECONDS
import io.github.sandeepvijayarao09.anu.core.model.clock
import io.github.sandeepvijayarao09.anu.core.voice.readSharedRecording
import io.github.sandeepvijayarao09.anu.core.voice.SharedRecording
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import java.time.ZoneId
import java.time.LocalDate
import java.time.Instant
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.sandeepvijayarao09.anu.core.ui.pushTransition
import io.github.sandeepvijayarao09.anu.core.ui.rememberReducedMotion
import io.github.sandeepvijayarao09.anu.feature.settings.PrivacyReportScreen
import io.github.sandeepvijayarao09.anu.feature.settings.SettingsPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.AnuTabBar
import io.github.sandeepvijayarao09.anu.core.ui.AnuTab
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sandeepvijayarao09.anu.feature.home.TalkScreen
import io.github.sandeepvijayarao09.anu.feature.home.ChatViewModel
import io.github.sandeepvijayarao09.anu.feature.memory.MemoryScreen
import io.github.sandeepvijayarao09.anu.feature.memory.MemoryViewModel
import io.github.sandeepvijayarao09.anu.feature.settings.SettingsScreen
import io.github.sandeepvijayarao09.anu.feature.settings.SettingsViewModel
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import io.github.sandeepvijayarao09.anu.feature.home.PDF_LOCKED
import io.github.sandeepvijayarao09.anu.feature.home.PDF_UNREADABLE
import io.github.sandeepvijayarao09.anu.feature.home.PdfRead
import io.github.sandeepvijayarao09.anu.feature.home.readPdf
import io.github.sandeepvijayarao09.anu.feature.home.readPhoto
import android.content.Intent
import io.github.sandeepvijayarao09.anu.core.model.Photo

class MainActivity : ComponentActivity() {
    /** A photo or recording shared into Anu, waiting to be attached in Talk (G2, G3). */
    private val shared = MutableStateFlow<Shared?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedItem(intent)?.let { shared.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as AnuApp).graph
        if (savedInstanceState == null) shared.value = sharedItem(intent)
        setContent {
            AnuTheme {
                AnuHome(graph, shared)
            }
        }
    }
}

private val TABS = listOf(AnuTab("Talk", AnuIcons.Talk), AnuTab("Memory", AnuIcons.Memory), AnuTab("Settings", AnuIcons.Settings))

private const val SETTINGS = 2

/**
 * The three pages: Talk, Memory and Settings (ADR-043, DESIGN.md). Swipe between them, or use the bar.
 * The Privacy report opens over whichever page the privacy indicator was tapped on.
 */
/** Photos, a recording or a PDF shared into Anu (G2, G3, ADR-094, ADR-095). A recording or a PDF comes alone. */
private const val PDF = "application/pdf"

private class Shared(val uris: List<Uri>, val audio: Boolean, val pdf: Boolean = false)

/** The photos, the recording or the PDF in a share into Anu, if that's what [intent] is. Several are photos only. */
private fun sharedItem(intent: Intent?): Shared? {
    val type = intent?.type ?: return null
    return when {
        intent.action == Intent.ACTION_SEND && (type.startsWith("image/") || type.startsWith("audio/") || type == PDF) -> {
            @Suppress("DEPRECATION")
            val uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)) ?: return null
            Shared(listOf(uri), audio = type.startsWith("audio/"), pdf = type == PDF)
        }
        intent.action == Intent.ACTION_SEND_MULTIPLE && type.startsWith("image/") -> {
            @Suppress("DEPRECATION")
            val uris = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            uris?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { Shared(it, audio = false) }
        }
        else -> null
    }
}

@Composable
private fun AnuHome(graph: AppGraph, shared: MutableStateFlow<Shared?>) {
    val context = LocalContext.current
    val pager = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()
    // Live talk goes on behind other apps with its notification, and the pill where app control is on (ADR-108).
    val chat = viewModel {
        ChatViewModel(
            graph.chat, graph.device::current, graph.approvals, graph.speechIn, graph.speechOut, facts = graph::factTexts,
            brainStatus = graph.onDevice.status, warm = graph::warmStart, recorder = graph.recorder, hears = graph.onDevice::available,
            behind = graph.liveTalk,
        ).also(graph.liveTalk::attach)
    }
    val memory = viewModel { MemoryViewModel(graph.memory) }
    // The prompt lab runs only the models on this phone, with nothing from the chat (G7, ADR-080).
    val lab = viewModel { LabViewModel(graph.onDevice, graph.onDevice.status) }
    // A shared photo or recording lands in Talk's composer, ready for a question about it (G2, G3). One collector
    // takes each share in turn: clearing the share inside an effect keyed on it would cancel the effect mid-read.
    LaunchedEffect(Unit) {
        shared.filterNotNull().collect { item ->
            shared.value = null
            if (item.pdf) {
                when (val read = withContext(Dispatchers.IO) { readPdf(context, item.uris.first(), chat.photosLeft) }) {
                    is PdfRead.Ready -> chat.attachPdf(read.pdf)
                    PdfRead.Locked -> chat.pdfUnusable(PDF_LOCKED)
                    PdfRead.Unreadable -> chat.pdfUnusable(PDF_UNREADABLE)
                }
            } else if (item.audio) {
                when (val read = withContext(Dispatchers.IO) { readSharedRecording(context, item.uris.first(), VOICE_NOTE_SECONDS) }) {
                    is SharedRecording.Ready -> chat.attachRecording(read.recording)
                    is SharedRecording.TooLong -> chat.recordingUnusable("This recording is ${clock(read.seconds)} long. Anu can write down up to 5 minutes.")
                    SharedRecording.Unreadable -> chat.recordingUnusable("This recording can't be read.")
                }
            } else {
                // Only as many as one message takes are read; attach says when the rest were left out (ADR-094).
                val read = withContext(Dispatchers.IO) { item.uris.take(Photo.MAX_IN_VIEW + 1).map { readPhoto(context.contentResolver, it) } }
                chat.attach(*read.filterNotNull().toTypedArray())
                if (null in read) chat.photoUnreadable()
            }
            pager.scrollToPage(0)
        }
    }
    val settings = viewModel {
        SettingsViewModel(
            graph.models, graph.geminiKey, graph.vaultActivity, graph.toolActivity,
            gmail = graph.gmailConnection,
            messaging = graph.messagingAccess,
            smsAvailable = Flavor.HAS_SMS,
            voice = graph.voice,
            mcp = graph.mcp,
            shopping = graph.stores,
            searchByMeaning = { graph.embedder() != null },
            installer = graph.installer,
            speeds = graph.speeds,
            thinkingSetting = graph.thinking,
            mac = graph.mac,
        )
    }
    // Live camera (ADR-111): "Ask Anu about this" comes back as a photo in the composer, handed over in memory, with
    // what the detector found in it for Gemma to read.
    val liveCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val frozen = LiveFrame.take()
        if (result.resultCode == android.app.Activity.RESULT_OK && frozen != null) chat.attachFrame(frozen.jpeg, frozen.found)
    }
    var micGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    // What the user was doing when Anu asked for the microphone: talking, or a voice note. It goes on once allowed.
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) afterMic?.invoke()
        afterMic = null
    }
    // The privacy indicator (DESIGN.md): what left the phone today, and a pulse each time one more thing does.
    val ledger by graph.ledger.entries.collectAsStateWithLifecycle()
    val opened = remember { Instant.now() }
    val today = LocalDate.now()
    val sentToday = ledger.count { it.at.atZone(ZoneId.systemDefault()).toLocalDate() == today }
    val awayPulse = ledger.count { it.at.isAfter(opened) }
    var privacyOpen by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    val openPrivacy: () -> Unit = { privacyOpen = true }
    val reducedMotion = rememberReducedMotion()
    // The assistant overlay shares this conversation, so show what was said there.
    LifecycleResumeEffect(Unit) {
        chat.reloadIfIdle()
        onPauseOrDispose {}
    }
    // Opening Talk warms its model while the user thinks of a question (EXP-1).
    LifecycleResumeEffect(pager.currentPage) {
        if (pager.currentPage == 0) graph.warmStart()
        onPauseOrDispose {}
    }
    Scaffold(
        containerColor = AnuTheme.colors.background,
        bottomBar = {
            AnuTabBar(
                TABS,
                pager.currentPage,
                onSelect = { index ->
                    // Choosing the tab you're on goes back to its first page.
                    if (index == pager.currentPage && index == SETTINGS && !privacyOpen) settingsPage = SettingsPage.MAIN
                    privacyOpen = false
                    scope.launch { pager.animateScrollToPage(index) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            // Only the visible page is composed. With its neighbours composed too, keyboard focus could land on
            // an off-screen page after a dialog closed, and the pager scrolled there (seen on the emulator).
            HorizontalPager(
                pager,
                Modifier.fillMaxSize().then(if (privacyOpen) Modifier.clearAndSetSemantics {} else Modifier),
                userScrollEnabled = !privacyOpen,
            ) { page ->
                when (page) {
                    0 -> TalkScreen(
                        chat, micGranted, onRequestMic = { then -> afterMic = then; askMic.launch(Manifest.permission.RECORD_AUDIO) }, sentToday = sentToday, onOpenPrivacy = openPrivacy,
                        awayPulse = awayPulse,
                        onOpenMemory = { factId ->
                            factId?.let(memory::show)
                            scope.launch { pager.animateScrollToPage(1) }
                        },
                        onLiveCamera = { liveCamera.launch(Intent(context, LiveCameraActivity::class.java)) },
                    )
                    1 -> MemoryScreen(memory, sentToday, onOpenPrivacy = openPrivacy)
                    else -> SettingsScreen(settings, sentToday, onOpenPrivacy = openPrivacy, page = settingsPage, onPage = { settingsPage = it }, lab = lab)
                }
            }
            AnimatedVisibility(
                visible = privacyOpen,
                enter = pushTransition(forward = true, reducedMotion).targetContentEnter,
                exit = pushTransition(forward = false, reducedMotion).initialContentExit,
            ) {
                // Registered after the page's own handlers, so Back closes the report before it pops a page underneath.
                BackHandler(enabled = privacyOpen) { privacyOpen = false }
                val back = if (pager.currentPage == SETTINGS) settingsPage.title else TABS[pager.currentPage].label
                PrivacyReportScreen(ledger, back = back, onBack = { privacyOpen = false })
            }
        }
    }
}
