package io.github.sandeepvijayarao09.anu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Debug builds only: adds or removes an agent on the user's network (ADR-105), until Settings has a page for them.
 * Only adb can send it (the DUMP permission):
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugA2a --es add http://10.0.2.2:8768/
 *   … --es remove http://10.0.2.2:8768/
 */
class DebugA2a : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as AnuApp).graph
        val done = goAsync()
        graph.scope.launch {
            try {
                intent.getStringExtra("add")?.let { if (!graph.agents.add(it)) Log.d("Anu", "A2A: not an http address") }
                intent.getStringExtra("remove")?.let(graph.agents::remove)
                graph.agents.refresh()
                Log.d("Anu", "A2A: agents ${graph.agents.tools().map { it.name }}, ${graph.agents.problems.size} not answering")
            } finally {
                done.finish()
            }
        }
    }
}
