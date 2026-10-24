package io.github.sandeepvijayarao09.anu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sandeepvijayarao09.anu.feature.home.ReplyTargets
import io.github.sandeepvijayarao09.anu.connector.messages.MessagingHub
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.useFrostedCardWindow
import io.github.sandeepvijayarao09.anu.feature.home.AssistantCard
import io.github.sandeepvijayarao09.anu.feature.home.ChatViewModel

/**
 * The assistant overlay (ADR-044): Anu's card over whatever app is showing. It listens at once, or sends
 * what followed "Hey Anu", and shares the one conversation with the Talk page. The window is the card,
 * frosted by the system's blur when it's allowed (ADR-051); the app underneath stays visible and lightly
 * dimmed, and tapping it closes the card.
 */
class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useFrostedCardWindow { blurred ->
            if (BuildConfig.DEBUG) Log.d("Anu", if (blurred) "Assistant card: frosted" else "Assistant card: opaque, the system has blurs off")
        }
        val graph = (application as AnuApp).graph
        // The power button or "Hey Anu": the model loads while the user speaks (EXP-1).
        graph.warmStart()
        val query = intent.getStringExtra(AnuVoiceInteractionService.EXTRA_QUERY)?.takeIf(String::isNotBlank)
        // From the Quick Settings tile, the screen is attached for the next question (ADR-108).
        val useScreen = intent.getBooleanExtra(AnuVoiceInteractionService.EXTRA_USE_SCREEN, false)
        setContent {
            AnuTheme {
                val chat = viewModel {
                    ChatViewModel(
                        graph.chat, graph.device::current, graph.approvals, graph.speechIn, graph.speechOut, facts = graph::factTexts,
                        brainStatus = graph.onDevice.status, warm = graph::warmStart, recorder = graph.recorder, hears = graph.onDevice::available,
                        screens = ScreenHandoff,
                        // A reply drafted about a chat can be sent while its notification's reply button is there (ADR-092).
                        replies = ReplyTargets { key -> MessagingHub.store.get(key)?.reply != null },
                    )
                }
                var micGranted by remember { mutableStateOf(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
                val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    micGranted = granted
                    if (granted) chat.listenWhenReady()
                }
                LaunchedEffect(Unit) {
                    if (useScreen) chat.useScreen()
                    when {
                        query != null -> chat.sendWhenReady(query)
                        micGranted -> chat.listenWhenReady()
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                AssistantCard(
                    chat,
                    micGranted,
                    onRequestMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenApp = {
                        startActivity(Intent(this@AssistantActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                )
            }
        }
    }

    /** The screen below the card is dropped when the card closes (EXP-2, ADR-083). */
    override fun onDestroy() {
        if (isFinishing) ScreenHandoff.clear()
        super.onDestroy()
    }
}
