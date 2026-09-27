package io.github.sandeepvijayarao09.anu.feature.memory

import io.github.sandeepvijayarao09.anu.core.memory.EventSource
import io.github.sandeepvijayarao09.anu.core.memory.Fact
import io.github.sandeepvijayarao09.anu.core.memory.FactOrigin
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import io.github.sandeepvijayarao09.anu.core.memory.USER
import java.time.Instant

/**
 * How a fact reads in the Memory list (DESIGN.md "Memory"): the fact is the label, and a second line
 * says how it relates to you.
 */
internal data class FactWords(
    /** The value ("Maya Chen"), or a sentence when the relation is a verb ("Prefers meetings in the morning"). */
    val label: String,
    /** "Your manager" or "Maya Chen's birthday". Null when the label is already a sentence. */
    val relationship: String?,
)

internal fun factWords(fact: Fact): FactWords = factWords(fact.subject, fact.predicate, fact.value)

internal fun factWords(subject: String, predicate: String, value: String): FactWords {
    val relation = predicate.replace('_', ' ').trim()
    val answer = value.trim().lowercase()
    val words = words(relation)
    val verb = words.isNotEmpty() && (words.first() in VERBS || (words.size > 1 && words.last() in PREPOSITIONS))
    // "likes", "lives in", "reports to": short enough to read as a sentence with the value.
    val short = verb && (words.size == 1 || words.last() in PREPOSITIONS)
    fun sentence(text: String) = if (subject == USER) sentenceCase(text) else "$subject $text"
    return when {
        answer in YES && subject == USER -> FactWords(sentenceCase(relation.removePrefix("is ").removePrefix("am ")), null)
        answer in YES -> FactWords("$subject $relation", null)
        answer in NO -> FactWords("${if (subject == USER) sentenceCase(relation) else "$subject · $relation"}: $value", null)
        // The model sometimes repeats the verb in the value ("prefers meetings in the morning"): that's the sentence.
        verb && words(value).firstOrNull() in VERBS -> FactWords(sentence(value.trim()), null)
        short -> FactWords(sentence("$relation ${value.trim()}"), null)
        verb -> FactWords(sentenceCase(value), if (subject == USER) sentenceCase(relation) else "$subject · $relation")
        subject == USER -> FactWords(sentenceCase(value), "Your $relation")
        else -> FactWords(sentenceCase(value), "$subject's $relation")
    }
}

private fun words(text: String): List<String> = text.lowercase().split(' ').filter(String::isNotEmpty)

/** Capitalizes the first letter, but leaves words like "iPhone" and "eBay" alone. */
internal fun sentenceCase(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed[0].isLowerCase()) return trimmed
    if (trimmed.length > 1 && trimmed[1].isUpperCase()) return trimmed
    return trimmed[0].titlecase() + trimmed.substring(1)
}

/** Where a fact came from, for its row: "You said · Sep 24", or "Learned from 2 messages". */
internal fun originText(fact: Fact, date: (Instant) -> String): String = when (fact.origin) {
    FactOrigin.USER_STATED -> "You said · ${date(fact.lastConfirmed)}"
    FactOrigin.INFERRED -> if (fact.sources.size == 1) "Learned from 1 message" else "Learned from ${fact.sources.size} messages"
}

/**
 * The row's second line. The relationship when there is one; where it came from when the row has no
 * "You said" on its right; and "Sensitive" for health, money and the like.
 */
internal fun rowSubtitle(fact: Fact, words: FactWords, date: (Instant) -> String): String = buildList {
    words.relationship?.let(::add)
    if (fact.origin == FactOrigin.INFERRED || words.relationship == null) add(originText(fact, date))
    if (fact.sensitive) add("Sensitive")
}.joinToString(" · ")

/** Whether a fact matches what's typed in search: every word must appear somewhere in how it reads. */
internal fun matches(fact: Fact, query: String): Boolean {
    val words = query.trim().lowercase().split(' ').filter(String::isNotBlank)
    if (words.isEmpty()) return true
    val reads = factWords(fact)
    val haystack = listOf(reads.label, reads.relationship.orEmpty(), fact.subject, fact.predicate.replace('_', ' '), fact.value, sectionName(fact.section))
        .joinToString(" ").lowercase()
    return words.all { it in haystack }
}

/** Short section names for the list. The profile keeps its longer titles for the model. */
internal fun sectionName(section: ProfileSection): String = when (section) {
    ProfileSection.IDENTITY -> "About you"
    ProfileSection.PEOPLE -> "People"
    ProfileSection.WORK -> "Work"
    ProfileSection.ROUTINES -> "Routines"
    ProfileSection.PREFERENCES -> "Preferences"
    ProfileSection.GOALS -> "Goals"
    ProfileSection.PLACES -> "Places"
}

internal fun sourceName(source: EventSource): String = when (source) {
    EventSource.CHAT -> "Your message"
    EventSource.STATEMENT -> "You asked Anu to remember"
    EventSource.EDIT -> "Your edit"
}

private val YES = setOf("true", "yes")
private val NO = setOf("false", "no")

private val PREPOSITIONS = setOf("in", "at", "to", "on", "for", "with", "from", "of", "by", "about", "into", "near")

private val VERBS = setOf(
    "am", "is", "was", "has", "have", "does", "can", "will",
    "likes", "like", "loves", "love", "enjoys", "enjoy", "prefers", "prefer", "dislikes", "dislike", "hates", "hate",
    "lives", "live", "works", "work", "wants", "want", "plans", "plan", "uses", "use", "owns", "own", "needs", "need",
    "speaks", "speak", "studies", "study", "drives", "drive", "eats", "eat", "drinks", "drink", "plays", "play",
    "goes", "go", "attends", "attend", "supports", "support", "follows", "follow", "reads", "read", "watches", "watch",
    "visits", "visit", "runs", "run", "walks", "walk", "cooks", "cook", "avoids", "avoid", "takes", "take",
    "knows", "know", "travels", "travel", "manages", "manage", "reports", "teaches", "commutes", "wakes", "sleeps",
    "exercises", "celebrates", "volunteers", "listens", "writes", "allergic", "born", "married", "moved", "grew",
)
