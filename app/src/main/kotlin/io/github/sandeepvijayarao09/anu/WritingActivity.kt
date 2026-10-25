package io.github.sandeepvijayarao09.anu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sandeepvijayarao09.anu.feature.writing.WritingSheet
import io.github.sandeepvijayarao09.anu.feature.writing.WritingViewModel

/**
 * "Anu" in the text-selection menu of any app (ARCHITECTURE §13, ADR-042). The selected text arrives in
 * [Intent.EXTRA_PROCESS_TEXT] and is treated as data only (§12.3). The result goes back only when the
 * user taps Replace, and never when the other app marked the text read-only.
 */
class WritingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as AnuApp).graph
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        setContent {
            AnuTheme {
                val writing = viewModel {
                    WritingViewModel(
                        cascade = graph.writing,
                        device = graph.device::current,
                        memory = { graph.memory.memoryBlock() },
                        tokens = graph.tokenCalibration,
                        system = graph.systemWriter,
                    ).apply { start(text, readOnly) }
                }
                WritingSheet(
                    writing,
                    onReplace = { result ->
                        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
                        finish()
                    },
                    onCopy = { result ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Anu", result))
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }
}
