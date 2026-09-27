package io.github.sandeepvijayarao09.anu.core.memory

import kotlin.math.sqrt

/**
 * Picks the facts that bear on a message (§8.3, ADR-054): the people it names, the user's relations it
 * mentions ("my sister"), topics it shares with a fact ("diet", "birthday"), and, with search by meaning
 * installed, facts close to it in meaning though they share no word ("plan dinner" and "diet: vegetarian",
 * ADR-055). Most relevant first, then most important, within [LIMIT] facts and [BUDGET_CHARS].
 */
object Relevance {
    const val LIMIT = 8
    const val BUDGET_CHARS = 600

    /**
     * To count by meaning alone, a fact must be this close (cosine) and stand out from the message's other
     * facts by [STANDS_OUT] standard deviations (ADR-056). Measured in spike S3 on EmbeddingGemma at 256
     * values: raw closeness overlaps (right facts 0.35–0.49, unrelated up to 0.44, small talk up to 0.52),
     * and some facts are close to everything. Both together found 11 of 12 right facts, with 3 wrong in 11
     * messages and none for small talk. Retune them for another model with the S3 eval.
     */
    const val CLOSE_ENOUGH = 0.37f
    const val STANDS_OUT = 1.25f

    /** With fewer facts than this, their spread says little, so closeness alone decides. */
    private const val SPREAD_FROM = 5

    /** How much closeness counts beside word matches: a close fact ranks like a relation match. */
    private const val MEANING_WEIGHT = 3f

    /** Whether a message refers to someone it doesn't name, so the message before it says who ("And for her birthday?"). */
    fun pointsBack(message: String): Boolean = Normalize.words(message).any { it in PRONOUNS }

    /** Whether a message carries any meaning to search for. "Thanks!" and "Good morning" don't, so they're never embedded. */
    fun hasContent(message: String): Boolean = asked(message).isNotEmpty()

    fun pick(
        message: String,
        facts: List<Fact>,
        similarity: Map<Long, Float> = emptyMap(),
        limit: Int = LIMIT,
        budgetChars: Int = BUDGET_CHARS,
    ): List<Fact> {
        val words = asked(message)
        if (words.isEmpty()) return emptyList()
        val close = closeInMeaning(similarity)
        val scored = facts.mapNotNull { fact ->
            val shared = score(fact, words)
            val near = fact.id in close
            if (shared == 0 && !near) null else fact to shared + if (near) MEANING_WEIGHT * similarity.getValue(fact.id) else 0f
        }
        val kept = mutableListOf<Fact>()
        var used = 0
        for ((fact, _) in scored.sortedWith(compareByDescending<Pair<Fact, Float>> { it.second }.thenBy(MemoryPipeline.IMPORTANCE) { it.first })) {
            if (kept.size == limit) break
            val cost = fact.text.length + 3
            if (used + cost > budgetChars) continue
            kept += fact
            used += cost
        }
        return kept
    }

    /** The facts close enough in meaning, and standing out from the rest once there are enough to compare. */
    private fun closeInMeaning(similarity: Map<Long, Float>): Set<Long> {
        if (similarity.isEmpty()) return emptySet()
        val values = similarity.values
        val mean = values.sum() / values.size
        val spread = sqrt(values.fold(0f) { sum, v -> sum + (v - mean) * (v - mean) } / values.size)
        return similarity.filter { (_, s) ->
            s >= CLOSE_ENOUGH && (values.size < SPREAD_FROM || spread > 0f && (s - mean) / spread >= STANDS_OUT)
        }.keys
    }

    /**
     * A name counts most: a person the message names, as a fact's subject or its value ("Maya" matches
     * "manager: Maya Chen"). A relation or topic word counts next, then any other shared word.
     */
    private fun score(fact: Fact, words: Set<String>): Int {
        val subject = if (fact.subject == USER) emptySet() else content(fact.subject)
        val relation = content(fact.predicate.replace('_', ' '))
        val value = content(fact.value)
        return 3 * (words intersect subject).size + 2 * (words intersect relation).size + (words intersect value).size
    }

    /** A message's words that carry meaning. A greeting's don't, though "Good morning" shares a word with "meetings: mornings". */
    private fun asked(message: String): Set<String> = content(message.replace(GREETING, " "))

    /**
     * The words that carry meaning, stemmed: function words are dropped before stemming, and so are numbers
     * under 100, too common to mean a match ("12 times 7" and "birthday: 12 March").
     */
    private fun content(text: String): Set<String> =
        Normalize.words(text).filter { it !in STOP_WORDS && !(it.length < 3 && it.all(Char::isDigit)) }.map(::stem).toSet()

    /** "sisters" and "sister's" match "sister". */
    private fun stem(word: String): String = if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word

    private val PRONOUNS = setOf("he", "she", "him", "her", "his", "hers", "they", "them", "their", "theirs")

    private val GREETING = Regex("\\bgood (morning|afternoon|evening|night)\\b", RegexOption.IGNORE_CASE)

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "so", "to", "for", "of", "in", "on", "at", "by", "with", "from", "about", "into",
        "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "done", "have", "has", "had", "will", "would", "can",
        "could", "should", "may", "might", "must", "shall", "i", "me", "my", "mine", "myself", "you", "your", "yours", "we", "our",
        "us", "he", "she", "him", "her", "his", "hers", "they", "them", "their", "it", "its", "this", "that", "these", "those",
        "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "any", "some", "all", "no", "not", "yes", "please",
        "tell", "give", "get", "make", "let", "know", "want", "need", "like", "just", "also", "too", "very", "up", "out", "now",
        "then", "there", "here", "user", "anu", "hi", "hey", "ok", "okay", "thank", "thanks", "remember", "s", "t", "ll", "re", "ve", "d", "m",
    )
}
