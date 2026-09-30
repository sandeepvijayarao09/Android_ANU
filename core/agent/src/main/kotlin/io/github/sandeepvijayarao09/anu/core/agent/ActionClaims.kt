package io.github.sandeepvijayarao09.anu.core.agent

/**
 * Spots answers that claim an action was done (ARCHITECTURE §7.4, ADR-027). It is deliberately narrow:
 * completed actions in the first person ("I've scheduled", "I sent") and finished states ("the invite
 * has been sent", "it's now on your calendar"). Proposals ("I can schedule…") and finds ("I've found
 * a slot") are not claims.
 */
object ActionClaims {
    private const val ACTS = "scheduled|booked|created|added|sent|invited|emailed|set up|put|moved|rescheduled|" +
        "cancell?ed|deleted|removed|replied|forwarded|messaged|texted"

    private val CLAIM = Regex(
        listOf(
            "\\b(i'?ve|i have|i|we'?ve|we have)\\s+(just\\s+|now\\s+|also\\s+|already\\s+|successfully\\s+)?($ACTS)\\b",
            // "I've set a reminder", "your list has been updated": seen after a declined voice note (ADR-085). Not a
            // bare "I set", which "Shall I set an alarm?" proposes.
            "\\b(i'?ve|i have|we'?ve|we have)\\s+(just\\s+|now\\s+|also\\s+|already\\s+|successfully\\s+)?set\\s+(a|an|the|your|it|that|this)\\b",
            "\\b(list|alarm|timer|reminder|note)s?\\s+(has|have)\\s+(just\\s+|now\\s+)?been\\s+(set|updated|saved|added|created)\\b",
            "\\b(invitation|invite|email|message|event|meeting|reminder)s?\\s+(has|have)\\s+(just\\s+|now\\s+)?been\\s+($ACTS)\\b",
            "\\b(is|are|it'?s)\\s+now\\s+(on|in)\\s+your\\s+calendar\\b",
            "\\b(added|put)\\s+(it|this|that)\\s+(to|on|in)\\s+your\\s+calendar\\b",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun claimsAction(answer: String): Boolean = CLAIM.containsMatchIn(answer.replace('’', '\''))

    /**
     * Model control markup that leaked into text, for example Gemma's `<|tool_call>`. It means the call
     * wasn't parsed, and it must never be shown as an answer.
     */
    private val MARKUP = Regex("""<\|tool_call>|<tool_call\|>|<\|"\|>|<\|tool_response>|<start_of_turn>|<end_of_turn>""")

    fun hasControlMarkup(answer: String): Boolean = MARKUP.containsMatchIn(answer)

    /** Added to the system prompt for the one repair attempt. */
    const val NOTHING_DONE_NOTE =
        "Important: in this conversation you have not created, sent or changed anything yet. Never say you have. " +
            "If the user wants it done, call the tool now; the user will be asked to approve it. " +
            "Otherwise, say what you propose and ask."

    /** Appended to an answer that still claims an action after repair and escalation. It is kept in the transcript too. */
    const val NOT_DONE_WARNING = "Note from Anu: nothing was actually created or sent."
}
