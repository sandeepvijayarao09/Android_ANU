package io.github.sandeepvijayarao09.anu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: summons the assistant with a question, exactly as "Hey Anu, <question>" does, for the emulator,
 * whose recognizer has no English pack. Only adb can send it (the DUMP permission):
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugAsk --es q "What's this?"
 */
class DebugAsk : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shown = AnuVoiceInteractionService.show(intent.getStringExtra("q"))
        Log.d("Anu", if (shown) "Debug ask: assistant shown" else "Debug ask: Anu isn't the assistant")
    }
}
