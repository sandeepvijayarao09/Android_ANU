package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.security.ShownSecrets

/**
 * Keeps secrets a photo showed out of the answer about it (I1, ADR-082). The answer streams until the first number
 * that could be a code; from there it's held until the answer is complete, then masked ([ShownSecrets]) and shown
 * whole, so a code is never shown and taken back. The reasoning, when the model thinks, is guarded the same way. The
 * saved chat keeps only the masked answer. [asked] is what the user wrote this turn.
 */
internal class SecretShield(private val asked: String, private val known: Collection<String> = emptyList()) {
    private val answer = Hold()
    private val thought = Hold()

    /** What to pass on for [event]: nothing while it's held, and at the end the rest, masked. */
    fun pass(event: AgentEvent): List<AgentEvent> = when (event) {
        is AgentEvent.TextDelta -> listOfNotNull(answer.next(event.text).takeIf { it.isNotEmpty() }?.let { event.copy(text = it) })
        is AgentEvent.Thinking -> listOfNotNull(thought.next(event.text).takeIf { it.isNotEmpty() }?.let { event.copy(text = it) })
        // What the model wrote before calling a tool, or an answer that failed its checks, is thrown away on screen too.
        is AgentEvent.ToolStarted, is AgentEvent.Discarded, is AgentEvent.Escalated -> {
            answer.reset()
            thought.reset()
            listOf(event)
        }
        is AgentEvent.Completed -> {
            val reasoning = thought.rest().takeIf { it.isNotEmpty() }?.let { rest ->
                AgentEvent.Thinking(event.tier, ShownSecrets.mask(rest, asked + "\n" + thought.all, known))
            }
            val masked = event.copy(
                text = ShownSecrets.mask(event.text, asked, known),
                added = event.added.map { if (it is ChatMessage.Model) it.copy(text = ShownSecrets.mask(it.text, asked, known)) else it },
            )
            listOfNotNull(reasoning, masked)
        }
        else -> listOf(event)
    }

    /** One stream's text: what's safe to show now, and what's held. */
    private class Hold {
        private val raw = StringBuilder()
        private var shown = 0
        private var holding = false

        val all: String get() = raw.toString()

        /** The part of the stream that's safe to show after [delta] arrives, possibly nothing. */
        fun next(delta: String): String {
            raw.append(delta)
            if (holding) return ""
            val stop = codeStart(raw, shown)
            val until = stop?.first ?: raw.length
            val out = raw.substring(shown, until)
            shown = until
            if (stop?.second == true) holding = true
            return out
        }

        /** What was held back. */
        fun rest(): String = raw.substring(shown)

        fun reset() {
            raw.clear()
            shown = 0
            holding = false
        }

        /**
         * Where the first possible code starts at or after [from], and whether it's long enough to hold the rest for
         * good (three digits or more). A shorter run of digits at the very end may still grow, so it waits too.
         */
        private fun codeStart(text: CharSequence, from: Int): Pair<Int, Boolean>? {
            var i = from
            while (i < text.length) {
                if (text[i].isDigit() && (i == 0 || !text[i - 1].isDigit())) {
                    var digits = 0
                    var j = i
                    while (j < text.length && (text[j].isDigit() || (text[j] == ' ' || text[j] == '-') && j + 1 < text.length && text[j + 1].isDigit())) {
                        if (text[j].isDigit()) digits++
                        j++
                    }
                    if (digits >= 3) return i to true
                    if (j == text.length || (j == text.length - 1 && (text[j] == ' ' || text[j] == '-'))) return i to false
                    i = j
                } else {
                    i++
                }
            }
            return null
        }
    }
}
