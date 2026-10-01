package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ReplyWords

/**
 * Whether a question asked from the assistant card is about the screen under it (EXP-2, design review D13): pointing
 * words such as "what's this", "this page", "this screen", "reply to this" or "on my screen". Only then, or after a tap
 * on "Use this screen", does Anu use the screen.
 */
object PointingWords {
    private val POINTING = Regex(
        "\\b(what(?:'s| is) (?:this|that|on (?:my|the) screen)|this (?:page|screen|message|email|article|post|chat|photo|picture|app)|" +
            "(?:on|in) (?:my|the) screen|reply to (?:this|that|it)|what am i (?:looking at|seeing)|explain (?:this|that)|" +
            "summari[sz]e (?:this|that)|translate (?:this|that)|read (?:this|that) (?:to|for) me)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asks(question: String): Boolean = POINTING.containsMatchIn(question) || ReplyWords.asks(question)
}
