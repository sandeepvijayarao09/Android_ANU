package io.github.sandeepvijayarao09.anu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.sandeepvijayarao09.anu.core.model.SystemWritingTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug builds only: Gemini Nano for AI Writing (ADR-116) from adb, as Settings → Models does it, without touching
 * the screen. Only adb can send it (the DUMP permission):
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugNano --ez download true
 *   … --es try PROOFREAD --es text 'made-up text'   (one task, timed; the log has the answer's length, never its text)
 * Each sends the status to `adb logcat -s Anu:D`.
 */
class DebugNano : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as AnuApp).graph
        val writer = graph.systemWriter
        val done = goAsync()
        graph.scope.launch {
            try {
                writer.refresh()
                if (intent.getBooleanExtra("download", false)) writer.download()
                intent.getStringExtra("try")?.let { task ->
                    val started = System.nanoTime()
                    val out = writer.write(SystemWritingTask.valueOf(task), intent.getStringExtra("text").orEmpty())
                    Log.d("Anu", "Nano: $task ${if (out == null) "fell back to Gemma" else "wrote ${out.length} chars"} in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
                Log.d("Anu", "Nano: ${writer.status.first()}")
            } finally {
                done.finish()
            }
        }
    }
}
