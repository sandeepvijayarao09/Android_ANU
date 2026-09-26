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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AnuHome(graph)
            }
        }
    }
}

/** The three pages: Talk, Memory and Settings (ADR-043). Swipe between them, or use the bar. */
@Composable
private fun AnuHome(graph: AppGraph) {
    val context = LocalContext.current
    val pages = listOf("Talk", "Memory", "Settings")
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val chat = viewModel { ChatViewModel(graph.chat, graph.device::current, graph.approvals, graph.speechIn, graph.speechOut) }
    val memory = viewModel { MemoryViewModel(graph.memory) }
    val settings = viewModel {
        SettingsViewModel(
            graph.models, graph.geminiKey, graph.ledger, graph.vaultActivity, graph.toolActivity,
            gmail = graph.gmailConnection,
            messaging = graph.messagingAccess,
            smsAvailable = Flavor.HAS_SMS,
            voice = graph.voice,
            mcp = graph.mcp,
            shopping = graph.stores,
        )
    }
    var micGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) chat.toggleListening()
    }
    // The assistant overlay shares this conversation, so show what was said there.
    LifecycleResumeEffect(Unit) {
        chat.reloadIfIdle()
        onPauseOrDispose {}
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                pages.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        icon = {},
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        // Only the visible page is composed. With its neighbours composed too, keyboard focus could land on
        // an off-screen page after a dialog closed, and the pager scrolled there (seen on the emulator).
        HorizontalPager(pager, Modifier.padding(padding)) { page ->
            when (page) {
                0 -> TalkScreen(chat, micGranted, onRequestMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) })
                1 -> MemoryScreen(memory)
                else -> SettingsScreen(settings)
            }
        }
    }
}
