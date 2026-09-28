package io.github.sandeepvijayarao09.anu

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as AnuApp).graph
        setContent {
            AnuTheme {
                AnuHome(graph)
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
@Composable
private fun AnuHome(graph: AppGraph) {
    val context = LocalContext.current
    val pager = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()
    val chat = viewModel { ChatViewModel(graph.chat, graph.device::current, graph.approvals, graph.speechIn, graph.speechOut) }
    val memory = viewModel { MemoryViewModel(graph.memory) }
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
        )
    }
    var micGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) chat.toggleListening()
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
                    0 -> TalkScreen(chat, micGranted, onRequestMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) }, sentToday = sentToday, onOpenPrivacy = openPrivacy, awayPulse = awayPulse)
                    1 -> MemoryScreen(memory, sentToday, onOpenPrivacy = openPrivacy)
                    else -> SettingsScreen(settings, sentToday, onOpenPrivacy = openPrivacy, page = settingsPage, onPage = { settingsPage = it })
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
