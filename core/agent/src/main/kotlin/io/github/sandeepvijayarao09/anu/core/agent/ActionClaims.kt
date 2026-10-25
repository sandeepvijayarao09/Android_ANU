package io.github.sandeepvijayarao09.anu.core.agent

/**
 * Spots answers that claim something was done (ARCHITECTURE §7.4, ADR-027, ADR-089). It is deliberately narrow:
 * - actions: completed ones in the first person ("I've scheduled", "I sent") and finished states ("the invite
 *   has been sent", "it's now on your calendar");
 * - memory: "I've noted…", "I've made a note", "I'll remember…", "I'll keep that in mind", "Noted!".
 * Proposals ("I can schedule…", "Shall I remember that?"), finds ("I've found a slot"), recall ("I remember
 * you're vegetarian") and other people's words ("as you noted", a quoted "Noted") are not claims.
 */
object ActionClaims {
    /**
     * What an answer can claim, and what backs it: any write backs an action, and only [REMEMBER_TOOL] backs
     * memory. [why] names the failure, [note] goes with the newest message for the repair attempt, and [undone] into
     * the warning.
     */
    enum class Claim(val why: String, internal val note: String, internal val undone: String) {
        ACTION("claimed an action that didn't happen", NOTHING_DONE_NOTE, "nothing was actually created or sent"),
        MEMORY("claimed to remember what wasn't saved", NOTHING_REMEMBERED_NOTE, "nothing was saved to memory"),
    }

    /** The only tool whose write backs a memory claim. It lives in `core:memory`, which depends on this module. */
    const val REMEMBER_TOOL = "memory_remember"

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

    /**
     * Up to eight words inside one sentence, as in "keep Alex's usual 6 PM finish time in mind", which E4B wrote on the
     * emulator. Four were too few.
     */
    private const val GAP = "([^\\s.!?]+\\s+){0,8}?"

    /** Words that acknowledge before a bare "Noted", as in "Got it, noted." */
    private const val ACK = "got it|got that|ok|okay|sure|sure thing|alright|all right|thanks|thank you|great|perfect|" +
        "understood|of course|absolutely|yes|lovely|wonderful|excellent"

    private val MEMORY_CLAIM = Regex(
        listOf(
            // Done, in the first person, unless it points back ("as I've noted"): "I've noted…", "I've made a note".
            "(?<!\\b(?:as|like)\\s)\\b(i'?ve|i have|we'?ve|we have)\\s+(just\\s+|now\\s+|also\\s+|already\\s+)?" +
                "(noted|remembered|memori[sz]ed|made\\s+(a\\s+)?notes?|taken\\s+(a\\s+)?note|jotted|written\\s+(that|this|it)\\s+down|" +
                "(kept|borne)\\s+${GAP}in\\s+mind|got\\s+(that|this|it)\\s+(noted|down))\\b",
            "\\bi\\s+(just\\s+|also\\s+)?(made\\s+(a\\s+)?note|took\\s+(a\\s+)?note|jotted|(wrote|noted)\\s+(that|this|it)\\s+down)\\b",
            "\\b(i'?ve|i have|i|we'?ve|we have)\\s+(just\\s+|now\\s+|also\\s+|already\\s+)?(saved|stored|added|put|kept|recorded)\\s+" +
                "${GAP}(to|in|into)\\s+(your\\s+|my\\s+|the\\s+)?memory\\b",
            "\\b(i'?ve|i have)\\s+(just\\s+|now\\s+|also\\s+)?updated\\s+(your|my)\\s+(memory|profile)\\b",
            // The same after another action: "I've booked lunch and noted that Maya is vegetarian". Not "and noted
            // the address", which is usually about the event.
            "(?<!\\b(?:as|like)\\s)\\b(i'?ve|i have|we'?ve|we have)\\s+[^.!?\\n]{1,80}?\\band\\s+(also\\s+)?" +
                "(noted\\s+that|made\\s+(a\\s+)?note|(saved|stored|added|kept)\\s+${GAP}(to|in|into)\\s+(your\\s+|my\\s+|the\\s+)?memory)\\b",
            // A promise: "I'll remember…", "I'll keep that in mind", "I'll make a note".
            "\\b(i'?ll|i will|i'?m going to)\\s+(always\\s+|definitely\\s+|be sure to\\s+|make sure to\\s+)?" +
                "(remember|memori[sz]e|(keep|bear)\\s+${GAP}in\\s+mind|make\\s+(a\\s+)?note|note\\s+(that|this|it|down)|" +
                "take\\s+(a\\s+)?note|jot\\s+(that|this|it)\\s+down|(save|store|add|keep)\\s+${GAP}(to|in|into)\\s+(your\\s+|my\\s+)?memory)\\b",
            "\\b(i'?ll|i will)\\s+[^.!?\\n]{1,80}?\\band\\s+(also\\s+)?(remember|(keep|bear)\\s+${GAP}in\\s+mind|make\\s+(a\\s+)?note)\\b",
            // "Noted!", "Got it, noted.", "Duly noted", as the answer's own words at the start of a sentence, maybe in bold.
            "(^|[.!?]\\s+|\\n\\s*)[*_]{0,2}(($ACK)\\s*[,.!—–-]?\\s+)?(duly\\s+)?noted[*_]{0,2}" +
                "(\\s*[!.,:;—–-]|\\s*(\\n|$)|\\s+(that|it|down)\\b)",
            // A finished state: "That's been noted", "It's now saved in your memory".
            "\\b(that|this|it)('s|\\s+is|\\s+has)\\s+(now\\s+|been\\s+|already\\s+){0,2}" +
                "(noted|remembered|(saved|stored|kept|added)\\s+(to|in)\\s+(your\\s+|my\\s+)?memory)\\b",
            "\\byour\\s+\\w+(\\s+\\w+)?\\s+(has|have)\\s+been\\s+(noted|remembered)\\b",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun claimsAction(answer: String): Boolean = CLAIM.containsMatchIn(normalized(answer))

    fun claimsMemory(answer: String): Boolean = MEMORY_CLAIM.containsMatchIn(normalized(answer))

    /** The claims in [answer] that [actions] don't back (ADR-027, ADR-089). */
    fun unbacked(answer: String, actions: List<ActionRecord>): Set<Claim> {
        val written = actions.filter { it.wrote && it.status == ActionRecord.Status.DONE }
        return buildSet {
            if (written.isEmpty() && claimsAction(answer)) add(Claim.ACTION)
            if (written.none { it.tool == REMEMBER_TOOL } && claimsMemory(answer)) add(Claim.MEMORY)
        }
    }

    private fun normalized(answer: String) = answer.replace('’', '\'')

    /**
     * Model control markup that leaked into text, for example Gemma's `<|tool_call>`. It means the call
     * wasn't parsed, and it must never be shown as an answer.
     */
    private val MARKUP = Regex("""<\|tool_call>|<tool_call\|>|<\|"\|>|<\|tool_response>|<start_of_turn>|<end_of_turn>""")

    fun hasControlMarkup(answer: String): Boolean = MARKUP.containsMatchIn(answer)

    /** Sent with the newest message for the one repair attempt (ADR-124). */
    const val NOTHING_DONE_NOTE =
        "Important: in this conversation you have not created, sent or changed anything yet. Never say you have. " +
            "If the user wants it done, call the tool now; the user will be asked to approve it. " +
            "Otherwise, say what you propose and ask."

    /**
     * Sent with the newest message for the one repair attempt after a memory claim (ADR-124). It allows what a tool
     * result confirms, so an answer after a save still passes. It gives no example answer: E4B copied the one it had
     * ("Thanks for telling me.") word for word, even after a declined save (`RepairNoteProbeTest`).
     */
    const val NOTHING_REMEMBERED_NOTE =
        "Important: nothing from the user's newest message has been saved to memory yet. " +
            "Don't say \"noted\", \"I'll remember\" or \"I'll keep that in mind\" unless a tool result confirms a save. " +
            "Answer without saying it's saved. If the user declined, say it won't be remembered."

    /**
     * Added after [NOTHING_REMEMBERED_NOTE] only when the turn offers [REMEMBER_TOOL]. A plain chat has no tool to
     * call, and a model told to call one there may write the call as text.
     */
    const val REMEMBER_HINT = "If the user asked you to remember it and didn't decline, call $REMEMBER_TOOL now."

    /** The notes for [claims], for the repair attempt and any stronger tier after it. [canRemember]: the turn offers [REMEMBER_TOOL]. */
    fun note(claims: Set<Claim>, canRemember: Boolean): String = claims.sorted().joinToString(" ") { claim ->
        if (claim == Claim.MEMORY && canRemember) "${claim.note} $REMEMBER_HINT" else claim.note
    }

    /** Appended to an answer that still claims something after repair and escalation. It is kept in the transcript too. */
    fun warning(claims: Set<Claim>): String = "Note from Anu: ${claims.sorted().joinToString(", and ") { it.undone }}."

    val NOT_DONE_WARNING = warning(setOf(Claim.ACTION))

    val NOT_REMEMBERED_WARNING = warning(setOf(Claim.MEMORY))
}
