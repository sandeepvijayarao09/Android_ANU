package io.github.sandeepvijayarao09.anu.core.router

/**
 * Picks the skill for a chat message, which decides the tools offered (§7.3: small models do better
 * with fewer tools). These are transparent keyword rules for v1. When in doubt it returns [Skill.CHAT],
 * which offers no tools.
 */
object SkillDetector {
    private val SCHEDULING = Regex(
        "\\b(calendar|schedul\\w*|meeting|meetings|appointment|agenda|free (time|slot)|busy|availab\\w*|" +
            "invite|reschedul\\w*|book (a|an|me)|remind me (at|on)|what'?s on my|my (day|week|morning|afternoon))\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [previous] is the skill of the last user turn. A short follow-up such as "yes please" or "make it
     * 3pm" keeps it, so the tools stay available. The emulator showed that dropping them made the model
     * write raw tool-call text.
     */
    fun detect(text: String, previous: Skill? = null): Skill = when {
        SCHEDULING.containsMatchIn(text) -> Skill.SCHEDULING
        previous != null && previous != Skill.CHAT && text.trim().length <= FOLLOW_UP_CHARS -> previous
        else -> Skill.CHAT
    }

    private const val FOLLOW_UP_CHARS = 80
}
