package io.github.sandeepvijayarao09.anu

import android.os.Bundle
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sandeepvijayarao09.anu.feature.home.ChatScreen
import io.github.sandeepvijayarao09.anu.feature.home.ChatViewModel
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

@Composable
private fun AnuHome(graph: AppGraph) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val chat = viewModel { ChatViewModel(graph.chat, graph.device::current, graph.approvals) }
    val settings = viewModel { SettingsViewModel(graph.models, graph.geminiKey, graph.ledger, graph.vaultActivity, graph.toolActivity, graph.gmailConnection) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = {}, label = { Text("Chat") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("Settings") })
            }
        },
    ) { padding ->
        when (tab) {
            0 -> ChatScreen(chat, Modifier.padding(padding))
            else -> SettingsScreen(settings, Modifier.padding(padding))
        }
    }
}
