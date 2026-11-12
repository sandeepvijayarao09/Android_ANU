package io.github.sandeepvijayarao09.anu.core.memory

/**
 * The people memory knows, and one name for each (ADR-145). A person is known by name: as a fact's subject
 * ("Maya Chen: birthday 13 March") or as the value of one of the user's relations to people ("manager: Maya"). A name
 * that fits exactly one known person is that person, "Maya" in "Maya Chen" or "Maya Chen" for "Maya", and the fuller
 * name is the one kept. Two people a name could mean stay apart: a wrong merge is worse than two entries.
 */
internal object People {
    /** The user's relations whose value is a person, by their canonical word (ADR-144). */
    val RELATIONS = setOf(
        "manager", "partner", "sister", "brother", "mother", "father", "mom", "dad", "son", "daughter", "friend", "best_friend",
        "colleague", "coworker", "boss", "aunt", "uncle", "cousin", "grandmother", "grandfather", "niece", "nephew", "roommate",
        "dentist", "doctor", "therapist", "teacher", "mentor", "neighbor", "assistant", "landlord", "trainer", "child", "sibling",
    )

    /** Every person [facts] name: subjects other than the user, and the values of the user's relations to people. */
    fun known(facts: List<Fact>): Set<String> = buildSet {
        for (fact in facts) {
            if (fact.subject != USER) add(fact.subject)
            if (fact.subject == USER && Normalize.predicate(fact.predicate) in RELATIONS) add(fact.value.trim())
        }
    }

    /**
     * The known person [name] means, or null when it fits none or more than one. A fit is the same name ignoring case,
     * or one name's words all in the other's, in order of appearance ("Maya" and "Maya Chen", "Dr. Okafor" and "Okafor").
     */
    fun match(name: String, known: Set<String>): String? {
        val words = words(name)
        if (words.isEmpty()) return null
        known.firstOrNull { it.equals(name.trim(), ignoreCase = true) }?.let { return it }
        val fits = known.filter { other -> words(other).let { theirs -> theirs.containsAll(words) || words.containsAll(theirs) } }
        return fits.singleOrNull()
    }

    /** Of two names for one person, the one to keep: the one with more words, else the longer. */
    fun fuller(a: String, b: String): String {
        val (wa, wb) = words(a).size to words(b).size
        return when {
            wa != wb -> if (wa > wb) a else b
            else -> if (a.trim().length >= b.trim().length) a else b
        }
    }

    /** Whether [text] names one of the [known] people by a word of their name that's long enough to mean them. */
    fun mentioned(text: String, known: Set<String>): Boolean {
        val said = words(text).toSet()
        return known.any { person -> words(person).any { it.length >= MIN_NAME && it in said } }
    }

    /**
     * The people [message] points to through the user's relations: "my manager's birthday" points to whoever the
     * manager fact names. The relation's word, or a word that means it, has to be in the message.
     */
    fun pointedTo(message: String, facts: List<Fact>): List<String> {
        val said = Normalize.words(message).map { Normalize.predicate(it) }.toSet()
        return facts.filter { it.subject == USER && Normalize.predicate(it.predicate) in RELATIONS && Normalize.predicate(it.predicate) in said }
            .map { it.value.trim() }
    }

    /**
     * Whether [text] states something lasting about a named person, someone memory may not know yet (ADR-146): a
     * capitalized name, then a verb of a life fact ("Priya moved to Pune", "Arjun Mehta joined Tailspin", "Leena's
     * birthday is…"). A question, or a request ("Book lunch with Maya", "Text Jordan that…"), isn't a statement, and a
     * pronoun or a word that starts a sentence ("The", "It") isn't a name. The extractor still decides what, if
     * anything, is a fact; this only keeps the message to learn from.
     */
    fun statedAbout(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.endsWith("?") || REQUEST.containsMatchIn(trimmed)) return false
        return STATEMENT.findAll(trimmed).any { match -> match.groupValues[1].split(' ').none { it.lowercase() in NOT_NAMES } }
    }

    /** A name of one or two capitalized words, a possessive or not, then a verb of a life fact. */
    private val STATEMENT = Regex(
        "\\b(\\p{Lu}\\p{Ll}+(?: \\p{Lu}\\p{Ll}+)?)(?:'s (?:birthday|new job|new place|partner|wife|husband|son|daughter|dog|cat))?\\s+" +
            "(?:just |finally |recently |now |also |has )?" +
            "(?:moved|lives|lived|works|worked|joined|started|got|is|was|loves|likes|hates|married|graduated|turned|left|quit|switched|became|prefers|" +
            "has|had|gets|plays|studies|teaches|runs|owns|retired|bought|adopted|transferred|relocated)\\b",
    )

    /** Words that start a request: the message asks for something, it doesn't say a fact. */
    private val REQUEST = Regex(
        "^(please |can you |could you |would you )?(book|schedule|text|call|email|send|remind|tell|ask|message|invite|add|set|find|show|open|draft|reply|write|plan|make|create|cancel|move|check)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Capitalized words that aren't a person's name: pronouns and words that start sentences. */
    private val NOT_NAMES = setOf(
        "i", "he", "she", "they", "we", "it", "you", "the", "this", "that", "there", "here", "what", "who", "which", "my", "our", "his", "her",
        "their", "today", "tomorrow", "yesterday", "everyone", "someone", "nobody", "everything", "nothing", "anu", "and", "but", "so", "also",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "mom", "dad",
    )

    /** A name's words, lowercase, without titles such as Dr. or Mr. */
    private fun words(name: String): List<String> = Normalize.words(name).filter { it !in TITLES }

    private val TITLES = setOf("dr", "mr", "mrs", "ms", "miss", "prof", "sir")

    /** A shorter word ("Al", "Jo") is too likely to be an ordinary word to mean someone. */
    private const val MIN_NAME = 3
}
