package io.github.sandeepvijayarao09.anu.core.model

/**
 * Whether a question asks for a reply to the conversation on the screen (EXP-2, ADR-092): "reply to this", "respond to
 * her", "draft a reply". Such an answer is the reply itself, which the assistant card offers to copy or send. "What's
 * the answer to this?" isn't one: it asks about the screen.
 */
object ReplyWords {
    private val REPLY = Regex(
        "\\b(?:reply|respond) to (?:this|that|it|him|her|them|the (?:message|text|chat|email))\\b|" +
            "\\b(?:draft|write|compose|suggest) (?:a |an |the |my )?(?:reply|response)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asks(question: String): Boolean = REPLY.containsMatchIn(question)

    /** What the model answers when the screen shows no message to reply to, so it isn't offered as a reply. */
    const val NOTHING_TO_REPLY = "No message to reply to."

    fun nothingToReply(answer: String): Boolean = answer.trim().trimEnd('.').equals(NOTHING_TO_REPLY.trimEnd('.'), ignoreCase = true)
}
