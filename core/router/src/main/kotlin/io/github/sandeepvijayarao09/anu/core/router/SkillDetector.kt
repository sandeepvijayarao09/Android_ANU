package io.github.sandeepvijayarao09.anu.core.router

/**
 * Picks the skill for a chat message, which decides the tools offered (§7.3: small models do better
 * with fewer tools). These are transparent keyword rules for v1. When in doubt it returns [Skill.CHAT],
 * which offers no tools.
 */
object SkillDetector {
    /**
     * Asking Anu to remember, recall or forget something about the user (§10): "Remember that…",
     * "What do you know about me?", "Forget that…", but not "forget it" or "I can't remember".
     */
    private val MEMORY = Regex(
        "^\\W*(please\\s+)?(remember|don'?t forget|note that|keep in mind|forget (that|about|what|my|i)\\b)|" +
            "\\b(what do you (know|remember) about|what have you learned|do you remember)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Words that only mean email. */
    private val EMAIL = Regex(
        "\\b(e-?mails?|e-?mailed|gmail|inbox|mail from|newsletter|subject line|cc)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Words that only mean texts and chat apps. Bare "text" and "chat" are left out: "shorten this text" is writing. */
    private val MESSAGES = Regex(
        "\\b(texts|texted|texting|text (me|him|her|them|back|from)|(a|her|his|their|last|latest|new|unread) texts?|['’]s texts?|" +
            "sms|whatsapp|telegram|messenger|signal app|on signal|slack|discord|group ?chats?|chats|dms?|messages?|messaged)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A message that starts by telling Anu to text someone: "Text Maya I'm late", but not "Text of the speech". */
    private val TEXT_COMMAND = Regex(
        "^\\W*(please\\s+)?(text|txt|message|msg|dm|whatsapp)\\b(?!\\s+of\\b)",
        RegexOption.IGNORE_CASE,
    )

    /** A phone number: 7 to 15 digits, and not a date. Texts are addressed by number and email never is. */
    private val PHONE = Regex("\\+?\\d[\\d ().-]{5,}\\d")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    private fun hasPhoneNumber(text: String) = PHONE.findAll(text).any { match ->
        match.value.count(Char::isDigit) in 7..15 && !DATE.containsMatchIn(match.value)
    }

    /** Words for either. They follow the conversation, and mean email when nothing says otherwise. */
    private val CORRESPONDENCE = Regex(
        "\\b(repl(y|ies|ied)|draft|forward (it|this|that|them)|unread|sent me|wrote (to )?me|get back to)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val SCHEDULING = Regex(
        "\\b(calendar|schedul\\w*|meeting|meetings|appointment|agenda|free (time|slot)|busy|availab\\w*|" +
            "invite|reschedul\\w*|book (a|an|me)|remind me (at|on)|what'?s on my|my (day|week|morning|afternoon))\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Buying things from the user's stores (ADR-047). Bare "order", "store" and "check out" are left out:
     * "in order to", "store this" and "check out this article" aren't shopping.
     */
    private val SHOPPING = Regex(
        "\\b(buy|buying|purchase|shop for|shopping|add (it |this |that |them )?to (my )?cart|checkout|" +
            "order (a|an|some|me|more|another)|in stock|price of|how much (is|are|does|do)|cheapest|in my stores?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The phone's own controls (G6, ADR-076). A whole command never reaches here: it runs with no model. This is for
     * the rest, such as "wake me at 6:30 and turn the flashlight off", so the model has the tools.
     */
    private val PHONE_CONTROLS = Regex("\\b(timers?|alarms?|wake me|flashlight|torch|do not disturb|dnd)\\b", RegexOption.IGNORE_CASE)

    /**
     * [previous] is the skill of the last user turn. A short follow-up such as "yes please" or "make it
     * 3pm" keeps it, so the tools stay available. The emulator showed that dropping them made the model
     * write raw tool-call text.
     */
    fun detect(text: String, previous: Skill? = null): Skill = byKeywords(text, previous) ?: when {
        previous != null && previous != Skill.CHAT && isFollowUp(text) -> previous
        else -> Skill.CHAT
    }

    /**
     * The skill for the last of [turns], the user's messages oldest first. Replaying the conversation
     * settles a "reply that 7 works" by the chat it belongs to, and a skill carries over at most
     * [MAX_FOLLOW_UPS] short follow-ups in a row, so it doesn't follow the user around.
     */
    fun detect(turns: List<String>): Skill = replay(turns.map(::Turn))

    /**
     * Like [detect], for turns whose skill may be fixed rather than read from their words, such as a voice note's
     * (ADR-078). A turn with a [Turn.skill] counts as if its words had found it.
     */
    fun replay(turns: List<Turn>): Skill {
        var skill = Skill.CHAT
        var followUps = 0
        for ((text, fixed) in turns.takeLast(REPLAY_TURNS)) {
            val found = fixed ?: byKeywords(text, skill)
            when {
                found != null -> { skill = found; followUps = 0 }
                skill != Skill.CHAT && isFollowUp(text) && followUps < MAX_FOLLOW_UPS -> followUps++
                else -> { skill = Skill.CHAT; followUps = 0 }
            }
        }
        return skill
    }

    /** The skill [text]'s own words name, or null when they name none, as for a message with no clear request. */
    fun keywords(text: String): Skill? = byKeywords(text, previous = null)

    /** A user message for [replay]: its words, and the skill it has regardless of them, if any. */
    data class Turn(val text: String, val skill: Skill? = null)

    private fun byKeywords(text: String, previous: Skill?): Skill? = when {
        // First: "Remember Maya's email is…" is about memory, not email.
        MEMORY.containsMatchIn(text) -> Skill.MEMORY
        EMAIL.containsMatchIn(text) -> Skill.EMAIL
        MESSAGES.containsMatchIn(text) || TEXT_COMMAND.containsMatchIn(text) -> Skill.MESSAGES
        // Before scheduling: a reply that proposes a time needs the reply tools, which include the calendar lookups.
        CORRESPONDENCE.containsMatchIn(text) ->
            if (previous == Skill.MESSAGES || hasPhoneNumber(text)) Skill.MESSAGES else Skill.EMAIL
        SCHEDULING.containsMatchIn(text) -> Skill.SCHEDULING
        SHOPPING.containsMatchIn(text) -> Skill.SHOPPING
        PHONE_CONTROLS.containsMatchIn(text) -> Skill.PHONE
        else -> null
    }

    private fun isFollowUp(text: String) = text.trim().length <= FOLLOW_UP_CHARS

    private const val FOLLOW_UP_CHARS = 80
    private const val MAX_FOLLOW_UPS = 3
    private const val REPLAY_TURNS = 20
}
