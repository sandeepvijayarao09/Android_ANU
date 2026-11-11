package io.github.sandeepvijayarao09.anu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: sends a message on Talk as if it were typed, for batch tests on a phone (`scripts/phone-checks.sh`),
 * since typing through adb loses words on Gboard. Talk must be open. Only adb can send it (the DUMP permission):
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugChat --es send "What is 12 times 9?"
 * The log says a message came, never what it said.
 */
class DebugChat : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("send")?.takeIf(String::isNotBlank) ?: return
        val sent = (context.applicationContext as AnuApp).graph.debugMessages.tryEmit(text)
        Log.d("Anu", if (sent) "Debug chat: message sent, ${text.length} chars" else "Debug chat: busy, not sent")
    }
}
