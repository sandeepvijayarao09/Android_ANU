package io.github.sandeepvijayarao09.anu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Debug builds only: pairs the user's Mac (ADR-113) from adb, since typing a 140-character code on an emulator loses
 * characters. Only adb can send it (the DUMP permission):
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugMac --es pair 'anu-mac://…'
 *   … --ez unpair true
 * The log says whether it paired, never the code.
 */
class DebugMac : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as AnuApp).graph
        val done = goAsync()
        graph.scope.launch {
            try {
                if (intent.getBooleanExtra("unpair", false)) graph.mac.unpair()
                intent.getStringExtra("pair")?.let { if (!graph.mac.pair(it)) Log.d("Anu", "Mac: not a pairing code") }
                Log.d("Anu", "Mac: ${graph.mac.paired.value?.let { "paired with $it" } ?: "not paired"}")
            } finally {
                done.finish()
            }
        }
    }
}
