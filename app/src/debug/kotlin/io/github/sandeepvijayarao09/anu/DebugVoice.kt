package io.github.sandeepvijayarao09.anu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: speech from adb for the emulator (`ShellSpeech`). Only adb can send it (the DUMP permission):
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugVoice --ez on true
 *   adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugVoice --es heard "What's 2 and 2?"
 */
class DebugVoice : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("on")) ShellSpeech.on = intent.getBooleanExtra("on", false)
        intent.getStringExtra("heard")?.let { ShellSpeech.heard.trySend(it) }
        Log.d("Anu", "Debug voice: ${if (ShellSpeech.on) "on" else "off"}${if (intent.hasExtra("heard")) ", words sent" else ""}")
    }
}
