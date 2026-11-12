package io.github.sandeepvijayarao09.anu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.sandeepvijayarao09.anu.core.vault.ItemType
import io.github.sandeepvijayarao09.anu.core.vault.NewVaultItem
import io.github.sandeepvijayarao09.anu.core.vault.Protection
import kotlinx.coroutines.launch

/**
 * Debug builds only: puts made-up items in the vault for the Vault page (ADR-135), since a real key mustn't be used
 * for a test. Both values are fixed here and bound to no host, so they can never be sent. Only adb can send it (DUMP):
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugVault --es add note
 *   … --es add key      (a fake API key, "test-key-not-real")
 *   … --ez clear true   (deletes the items this added)
 * The log says what was added, never a value.
 */
class DebugVault : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as AnuApp).graph
        val done = goAsync()
        graph.scope.launch {
            try {
                // A secure note needs the screen-lock level, which a phone without a screen lock can't make.
                runCatching { add(graph, intent) }.onFailure { Log.d("Anu", "Debug vault: not added, ${it::class.simpleName}") }
                Log.d("Anu", "Debug vault: ${graph.vault.list().count { it.service == SERVICE }} test items")
            } finally {
                done.finish()
            }
        }
    }

    private suspend fun add(graph: AppGraph, intent: Intent) {
        if (intent.getBooleanExtra("clear", false)) {
            graph.vault.list().filter { it.service == SERVICE }.forEach { graph.vault.delete(it.ref) }
        }
        when (intent.getStringExtra("add")) {
            "note" -> graph.vault.put(NewVaultItem(ItemType.SECURE_NOTE, "Test note", SERVICE, "made-up note: locker 0000"))
            "key" -> graph.vault.put(
                NewVaultItem(ItemType.API_KEY, "Test API key", SERVICE, "test-key-not-real", protection = Protection.DEVICE_UNLOCKED),
            )
        }
    }

    private companion object {
        const val SERVICE = "Anu test"
    }
}
