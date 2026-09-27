package io.github.sandeepvijayarao09.anu.core.memory

/**
 * Picks the facts that bear on a message (§8.3, ADR-054): the people it names, the user's relations it
 * mentions ("my sister"), and topics it shares with a fact ("diet", "birthday"). Most relevant first,
 * then most important, within [LIMIT] facts and [BUDGET_CHARS]. Search by meaning is Planned (spike S3).
 */
object Relevance {
    const val LIMIT = 8
    const val BUDGET_CHARS = 600

    fun pick(message: String, facts: List<Fact>, limit: Int = LIMIT, budgetChars: Int = BUDGET_CHARS): List<Fact> {
        val words = content(message)
        if (words.isEmpty()) return emptyList()
        val scored = facts.mapNotNull { fact -> score(fact, words).takeIf { it > 0 }?.let { fact to it } }
        val kept = mutableListOf<Fact>()
        var used = 0
        for ((fact, _) in scored.sortedWith(compareByDescending<Pair<Fact, Int>> { it.second }.thenBy(MemoryPipeline.IMPORTANCE) { it.first })) {
            if (kept.size == limit) break
            val cost = fact.text.length + 3
            if (used + cost > budgetChars) continue
            kept += fact
            used += cost
        }
        return kept
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

    /** The words that carry meaning, stemmed: function words are dropped before stemming. */
    private fun content(text: String): Set<String> = Normalize.words(text).filter { it !in STOP_WORDS }.map(::stem).toSet()

    /** "sisters" and "sister's" match "sister". */
    private fun stem(word: String): String = if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word

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
