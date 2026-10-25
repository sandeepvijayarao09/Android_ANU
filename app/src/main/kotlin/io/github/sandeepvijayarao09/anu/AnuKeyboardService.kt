package io.github.sandeepvijayarao09.anu

import android.util.Log
import io.github.sandeepvijayarao09.anu.connector.messages.MessagingHub
import io.github.sandeepvijayarao09.anu.feature.keyboard.ChatContext
import io.github.sandeepvijayarao09.anu.feature.keyboard.KeyboardHost
import io.github.sandeepvijayarao09.anu.feature.keyboard.KeyboardService

/**
 * Anu's keyboard (ARCHITECTURE §13, ADR-109), wired to the app: writing on the phone only, the memory block for the
 * user's voice, and for Reply the newest chat that the field's app showed in a notification (ADR-031).
 */
class AnuKeyboardService : KeyboardService() {
    override fun host(): KeyboardHost {
        val graph = (application as AnuApp).graph
        return KeyboardHost(
            cascade = graph.keyboardWriting,
            device = graph.device::current,
            memory = { graph.memory.memoryBlock() },
            chat = { app ->
                MessagingHub.store.list().firstOrNull { it.app == app && it.lines.isNotEmpty() }?.let { chat ->
                    ChatContext(chat.title, chat.lines.map { ChatContext.Line(it.sender, it.text, it.fromMe) })
                }
            },
            debug = if (BuildConfig.DEBUG) { line -> Log.d("Anu", line) } else null,
            system = graph.systemWriter,
        )
    }
}
