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
    /** A text written for the user to send from the phone: "draft a message to Maya", "write a text for Raj" (ADR-099). */
    private val TEXT_DRAFT = Regex(
        "\\b(draft|write|compose|prepare|type up)\\b(\\s+\\S+){0,3}?\\s+(text|message|sms)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val CORRESPONDENCE = Regex(
        "\\b(repl(y|ies|ied)|draft|forward (it|this|that|them)|unread|sent me|wrote (to )?me|get back to)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val SCHEDULING = Regex(
        "\\b(calendar|schedul\\w*|meeting|meetings|appointment|agenda|free (time|slot)|busy|availab\\w*|" +
            "invite|reschedul\\w*|book (a|an|me)|what'?s on my|my (day|week|morning|afternoon))\\b",
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
    private val PHONE_CONTROLS = Regex(
        "\\b(timers?|alarms?|wake me|flashlight|torch|do not disturb|dnd|brightness|brighter|dimmer|volume|ringer|sound level)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Reminders and other apps through Android's intents (ADR-107): "remind me on Friday to pay rent", "call Maya", "open
     * Spotify and…", "directions to the airport", "share this". Like the controls they come last, after the calendar's words,
     * so "remind me about the meeting" is still an event. They don't count as an area for ADK's agents, whose device agent
     * doesn't have these tools, and "call" and "open" are too common to send a request to the coordinator. "Remind me what
     * her name was" and "call me Sam" are neither.
     */
    private val PHONE_INTENTS = Regex(
        "^\\W*(please\\s+)?(call(?! me\\b)|dial|open|launch)\\b|" +
            "\\b(remind me (to|at|in|on|by|today|tonight|tomorrow|about|that|next)|reminders?|navigate|directions|take me to|drive me to|" +
            "share (this|that|it)|share (a|the|my) \\w+|in (google )?maps)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The user's notes and address book, which the device skill also offers (ADR-093): "add eggs to my groceries note",
     * "save Raj's number". "Note that…" is memory, matched first.
     */
    private val NOTE_WORDS = Regex("\\b(notes?|notepad)\\b", RegexOption.IGNORE_CASE)
    private val CALENDAR_WORDS = Regex("\\b(calendar|meetings?|appointments?|invite|schedul\\w*|reschedul\\w*)\\b", RegexOption.IGNORE_CASE)

    private val NOTES_AND_CONTACTS = Regex(
        "\\b(notes?|notepad|(to|in|from) (my )?contacts|new contact|contact card|(save|add|store) (his|her|their|\\w+['’]s) (number|phone|email))\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [previous] is the skill of the last user turn. A short follow-up such as "yes please" or "make it
     * 3pm" keeps it, so the tools stay available. The emulator showed that dropping them made the model
     * write raw tool-call text.
     */
    fun detect(text: String, previous: Skill? = null): Skill = byKeywords(text, previous) ?: when {
        previous != null && previous !in ONE_OFF && previous != Skill.CHAT && isFollowUp(text) -> previous
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
                skill != Skill.CHAT && skill !in ONE_OFF && isFollowUp(text) && followUps < MAX_FOLLOW_UPS -> followUps++
                else -> { skill = Skill.CHAT; followUps = 0 }
            }
        }
        return skill
    }

    /** The skill [text]'s own words name, or null when they name none, as for a message with no clear request. */
    fun keywords(text: String): Skill? = byKeywords(text, previous = null)

    /** A user message for [replay]: its words, and the skill it has regardless of them, if any. */
    data class Turn(val text: String, val skill: Skill? = null)

    /**
     * A request that names another app to act in (ADR-098): "in the Test Shop app, add…", "open the Clock app and…".
     * The word "app" is required, so "add it to my cart" stays shopping, with the user's stores.
     */
    private val IN_AN_APP = Regex(
        "\\b(in|on|using|with|open|launch|go to) (the |my )?(?!(a|an|the|my|this|that|your|our|their|his|her|new|one|an?other) app\\b)" +
            "[\\p{L}\\p{N}][\\p{L}\\p{N}&.'’ +-]{0,30}? app\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A daily brief (ADR-104): "brief me", "my daily brief", "what's my day look like". */
    private val BRIEF = Regex(
        "\\b(brief me|(daily|morning|evening) brief(ing)?|what('?s| is| does) my day( look(s)? like)?|catch me up on (my|the) day)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The areas a request can span. Two or more in one message, such as "check my calendar and text Maya I'm late", go to
     * ADK's coordinator and its specialists (ADR-104). Email isn't counted: its skill already offers the calendar.
     */
    private fun areas(text: String): Set<Skill> = buildSet {
        // As below: "what's on my groceries note" is a note, not the calendar, unless the calendar is named.
        val noteOnly = NOTE_WORDS.containsMatchIn(text) && !CALENDAR_WORDS.containsMatchIn(text)
        if (!noteOnly && (SCHEDULING.containsMatchIn(text) || CALENDAR_WORDS.containsMatchIn(text))) add(Skill.SCHEDULING)
        if (MESSAGES.containsMatchIn(text) || TEXT_COMMAND.containsMatchIn(text) || TEXT_DRAFT.containsMatchIn(text) || TEXT_TO.containsMatchIn(text)) add(Skill.MESSAGES)
        // "Add a note to my 3pm meeting on the calendar" is the meeting's note, so with the calendar named notes don't count.
        if (PHONE_CONTROLS.containsMatchIn(text) || NOTES_AND_CONTACTS.containsMatchIn(text) && !CALENDAR_WORDS.containsMatchIn(text)) add(Skill.PHONE)
    }

    /** "ask the Home Lab agent", "tell my robot agent" (ADR-105). */
    private val ASK_AGENT = Regex(
        "\\b(ask|tell|have) (the |my )?[\\p{L}\\p{N}][\\p{L}\\p{N} '-]{0,30}? agent\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "text Maya", "message Raj" in the middle of a request. */
    private val TEXT_TO = Regex("\\b(text|message) [A-Z][a-z]+\\b")

    private fun byKeywords(text: String, previous: Skill?): Skill? = when {
        // First: "Remember Maya's email is…" is about memory, not email.
        MEMORY.containsMatchIn(text) -> Skill.MEMORY
        // Before email, messages and shopping: "in the Gmail app, archive…" means tapping in that app.
        IN_AN_APP.containsMatchIn(text) -> Skill.APPS
        BRIEF.containsMatchIn(text) -> Skill.BRIEF
        // "Ask the Home Lab agent…": an agent on the user's network, which the coordinator calls (ADR-105).
        ASK_AGENT.containsMatchIn(text) -> Skill.AGENTS
        // Before each single area: a request across two of them gets the specialists of both (ADR-104).
        !EMAIL.containsMatchIn(text) && areas(text).size >= 2 -> Skill.AGENTS
        EMAIL.containsMatchIn(text) -> Skill.EMAIL
        MESSAGES.containsMatchIn(text) || TEXT_COMMAND.containsMatchIn(text) -> Skill.MESSAGES
        // "Draft a text to Maya": the draft goes in the phone's messaging app, which the messages skill offers (ADR-099).
        TEXT_DRAFT.containsMatchIn(text) -> Skill.MESSAGES
        // Before scheduling: a reply that proposes a time needs the reply tools, which include the calendar lookups.
        CORRESPONDENCE.containsMatchIn(text) ->
            if (previous == Skill.MESSAGES || hasPhoneNumber(text)) Skill.MESSAGES else Skill.EMAIL
        // Before scheduling, whose "what's on my" would take "What's on my groceries note?" to tools without notes: on
        // the emulator the model then made the note up (S12). The calendar's own words keep it scheduling.
        NOTE_WORDS.containsMatchIn(text) && !CALENDAR_WORDS.containsMatchIn(text) -> Skill.PHONE
        SCHEDULING.containsMatchIn(text) -> Skill.SCHEDULING
        SHOPPING.containsMatchIn(text) -> Skill.SHOPPING
        PHONE_CONTROLS.containsMatchIn(text) || NOTES_AND_CONTACTS.containsMatchIn(text) || PHONE_INTENTS.containsMatchIn(text) -> Skill.PHONE
        else -> null
    }

    private fun isFollowUp(text: String) = text.trim().length <= FOLLOW_UP_CHARS

    /**
     * Skills a follow-up never keeps (ADR-104): a brief and a request across areas each run once on ADK's agents, which
     * don't see the chat. Carried over, "What's a good name for a cat?" after "Ask the Home Lab agent…" went to the
     * agents, and "Give me one more" lost the question before it.
     */
    private val ONE_OFF = setOf(Skill.AGENTS, Skill.BRIEF)

    private const val FOLLOW_UP_CHARS = 80
    private const val MAX_FOLLOW_UPS = 3
    private const val REPLAY_TURNS = 20
}
