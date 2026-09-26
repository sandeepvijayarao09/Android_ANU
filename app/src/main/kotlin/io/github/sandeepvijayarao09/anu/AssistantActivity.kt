package io.github.sandeepvijayarao09.anu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import io.github.sandeepvijayarao09.anu.feature.home.AssistantCard
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sandeepvijayarao09.anu.feature.home.ChatViewModel

/**
 * The assistant overlay (ADR-044): the orb in a sheet over whatever app is showing. It listens at once,
 * or sends what followed "Hey Anu". It shares the one conversation with the Talk page.
 */
class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as AnuApp).graph
        val query = intent.getStringExtra(AnuVoiceInteractionService.EXTRA_QUERY)?.takeIf(String::isNotBlank)
        setContent {
            AnuTheme {
                val chat = viewModel { ChatViewModel(graph.chat, graph.device::current, graph.approvals, graph.speechIn, graph.speechOut) }
                var micGranted by remember { mutableStateOf(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
                val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    micGranted = granted
                    if (granted) chat.listenWhenReady()
                }
                LaunchedEffect(Unit) {
                    when {
                        query != null -> chat.sendWhenReady(query)
                        micGranted -> chat.listenWhenReady()
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                // The app underneath stays visible and lightly dimmed; tapping it closes the card.
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::finish),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AssistantCard(
                        chat,
                        micGranted,
                        onRequestMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenApp = {
                            startActivity(Intent(this@AssistantActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                        modifier = Modifier.navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}
