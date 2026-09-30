package io.github.sandeepvijayarao09.anu.core.memory

/**
 * A fact as a sentence to the user, for approvals and the memory tools' summaries (ADR-088): "You buy oat
 * milk every week", "Your manager is Maya Chen", "Priya lives in Pune". A relation is the model's
 * snake_case, such as `buys_at` or `is_vegetarian`, which nobody should have to read.
 * - The value is always there, word for word. So is the relation, unless the value says its verb again,
 *   as "buy oat milk every week" does for `buys_at`: then the verb is said once, in the right person.
 * - It's made from the fact's own parts, never by a model, so an approval shows exactly what will be kept
 *   (I2). A relation that doesn't read as English stays a plain label, such as "Smoker: no".
 */
fun factSentence(subject: String, predicate: String, value: String): String = phrase(subject, predicate, value).sentence

/**
 * The same fact inside a summary, after "Remember" or "Forget": "that you buy oat milk every week", or
 * "your weekly shop: buy oat milk". A plain label is quoted: `"Smoker: no"`.
 */
fun factClause(subject: String, predicate: String, value: String): String = phrase(subject, predicate, value).clause

/** Capitalizes the first letter, but leaves words like "iPhone" and "eBay" alone. */
fun sentenceCase(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed[0].isLowerCase()) return trimmed
    if (trimmed.length > 1 && trimmed[1].isUpperCase()) return trimmed
    return trimmed[0].titlecase() + trimmed.substring(1)
}

/** The words facts are made of, shared by approvals and the Memory tab, so a fact reads alike in both. */
object FactGrammar {
    /** First words that make a relation a verb, as in `likes` or `lives_in`. */
    val VERBS = setOf(
        "am", "is", "was", "has", "have", "does", "can", "will",
        "likes", "like", "loves", "love", "enjoys", "enjoy", "prefers", "prefer", "dislikes", "dislike", "hates", "hate",
        "lives", "live", "works", "work", "wants", "want", "plans", "plan", "uses", "use", "owns", "own", "needs", "need",
        "speaks", "speak", "studies", "study", "drives", "drive", "eats", "eat", "drinks", "drink", "plays", "play",
        "goes", "go", "attends", "attend", "supports", "support", "follows", "follow", "reads", "read", "watches", "watch",
        "visits", "visit", "runs", "run", "walks", "walk", "cooks", "cook", "avoids", "avoid", "takes", "take",
        "knows", "know", "travels", "travel", "manages", "manage", "reports", "teaches", "commutes", "wakes", "sleeps",
        "exercises", "celebrates", "volunteers", "listens", "writes", "allergic", "born", "married", "moved", "grew",
        "buys", "buy", "orders", "pays", "wears", "spends", "saves", "joins", "belongs", "comes", "stays", "sends",
        "gives", "brings", "grows", "feeds", "collects", "bakes", "swims", "sees", "learns", "keeps", "gets", "makes",
    )

    /** Last words that make a relation want its value next, as in `lives_in`. */
    val PREPOSITIONS = setOf("in", "at", "to", "on", "for", "with", "from", "of", "by", "about", "into", "near", "as")

    /** Whether two words are one verb, as "buys" and "buy" are. */
    fun sameVerb(a: String, b: String): Boolean = lemma(a) == lemma(b)

    /** "buy" as "buys", for someone else. Forms that already agree, and past ones such as "moved", stay as they are. */
    fun thirdPerson(verb: String): String = when {
        verb == "have" -> "has"
        verb == "do" -> "does"
        verb == "am" || verb == "are" -> "is"
        verb == "were" -> "was"
        verb in FIXED || isPast(verb) || base(verb) != verb -> verb
        verb.endsWith('s') || verb.endsWith("sh") || verb.endsWith("ch") || verb.endsWith('x') || verb.endsWith('z') || verb.endsWith('o') -> verb + "es"
        verb.length > 1 && verb.endsWith('y') && verb[verb.length - 2] !in "aeiou" -> verb.dropLast(1) + "ies"
        else -> verb + "s"
    }

    /** "buys" as "buy", "studies" as "study". Anything else stays as it is. */
    internal fun base(verb: String): String = when {
        verb in FIXED -> verb
        verb.length > 4 && verb.endsWith("ies") -> verb.dropLast(3) + "y"
        SIBILANT_ENDINGS.any { verb.endsWith(it) } || verb.endsWith("oes") -> verb.dropLast(2)
        verb.length > 2 && verb.endsWith('s') && !verb.endsWith("ss") && !verb.endsWith("us") && !verb.endsWith("is") -> verb.dropLast(1)
        else -> verb
    }

    internal fun isPast(verb: String) = verb in IRREGULAR_PAST || (verb.endsWith("ed") && !verb.endsWith("eed"))

    /** Whether a value starts with a verb, as "buy oat milk" does, so "Your weekly shop is buy oat milk" isn't said. */
    internal fun startsWithVerb(word: String?) = word != null && word !in COPULAR &&
        (word in VERBS || word in HELPERS || base(word) in VERBS || thirdPerson(word) in VERBS)

    private fun lemma(word: String) = when (word) {
        "is", "am", "are" -> "be"
        "was", "were" -> "was"
        "has", "have" -> "have"
        "does", "do" -> "do"
        else -> base(word)
    }

    private val SIBILANT_ENDINGS = listOf("sses", "shes", "ches", "xes", "zzes")
}

private class Phrase(val sentence: String, val clause: String)

private fun phrase(subject: String, predicate: String, value: String): Phrase {
    val me = subject == USER
    val relation = predicate.replace('_', ' ').lowercase().split(' ').filter(String::isNotEmpty)
    val shown = value.trim()
    val parts = shown.split(WHITESPACE).filter(String::isNotEmpty)
    val words = parts.map { part -> part.lowercase().trim { !it.isLetterOrDigit() } }
    val answer = shown.lowercase()
    val you = if (me) "you" else subject

    fun said(text: String) = Phrase(sentenceCase(text), "that $text")
    fun label(): Phrase {
        val plain = when (answer) {
            in YES -> "yes"
            in NO -> "no"
            else -> shown
        }
        val text = if (me) sentenceCase("${relation.joinToString(" ")}: $plain") else "$subject · ${relation.joinToString(" ")}: $plain"
        return Phrase(text, "\"$text\"")
    }

    // Adverbs stay with the verb: `never_eats` is "You never eat".
    val adverbs = relation.takeWhile { it in ADVERBS }
    val core = relation.drop(adverbs.size)
    val head = core.firstOrNull() ?: return label()
    val tail = core.drop(1)
    // `lives_in`, `is_a`: the value comes straight after the relation.
    val open = tail.isNotEmpty() && (core.last() in FactGrammar.PREPOSITIONS || core.last() in ARTICLES)
    if (!isVerbal(head, tail, open)) {
        if (adverbs.isNotEmpty() || open || answer in YES || answer in NO) return label()
        val your = if (me) "your" else "$subject's"
        val noun = core.joinToString(" ")
        return if (FactGrammar.startsWithVerb(words.firstOrNull())) {
            Phrase(sentenceCase("$your $noun: $shown"), "$your $noun: $shown")
        } else {
            said("$your $noun ${if (plural(core.last())) "are" else "is"} $shown")
        }
    }
    val verb = verbPhrase(adverbs, head, tail, me)
    val lead = words.takeWhile { it in ADVERBS }.size
    return when {
        answer in YES -> if (open || (tail.isEmpty() && head !in COPULAR)) label() else said("$you $verb")
        answer in NO -> negated(adverbs, head, tail, me)?.let { said("$you $it") } ?: label()
        // The value says the verb again: "buy oat milk every week" for `buys_at` is "You buy oat milk every week".
        words.getOrNull(lead)?.let { FactGrammar.sameVerb(head, it) } == true -> {
            val moreAdverbs = parts.take(lead).map(String::lowercase).filter { it !in adverbs }
            said("$you ${verbPhrase(adverbs + moreAdverbs, head, parts.drop(lead + 1), me)}")
        }
        tail.isEmpty() || open || core.last().let { it in FactGrammar.VERBS || FactGrammar.base(it) in FactGrammar.VERBS } -> said("$you $verb $shown")
        else -> said("$you $verb: $shown")
    }
}

/**
 * Whether a relation starts with a verb. A plain form such as "work" often starts a noun (`work_address`,
 * `travel_plans`), so only a few count. Before a preposition, any present or past form does (`shops_at`).
 */
private fun isVerbal(head: String, tail: List<String>, open: Boolean): Boolean {
    val present = FactGrammar.base(head) != head
    return when {
        head in HELPERS || head in COPULAR -> true
        open -> present || FactGrammar.isPast(head) || head in FactGrammar.VERBS
        tail.isEmpty() && head in NOUNS_ALONE -> false
        present -> head in FactGrammar.VERBS || FactGrammar.base(head) in FactGrammar.VERBS
        FactGrammar.isPast(head) -> tail.isEmpty()
        else -> head in PLAIN_VERBS && (tail.isEmpty() || tail.last() in FactGrammar.VERBS)
    }
}

/** The relation as a verb said of the user ("buy at", "are allergic to") or of someone else ("buys at"). */
private fun verbPhrase(adverbs: List<String>, head: String, rest: List<String>, me: Boolean): String {
    val verb = conjugate(head, me).split(' ')
    // Adverbs follow "be" and modals ("are never late", "can often drive"), and come before other verbs ("never eat").
    val after = verb.size > 1 || head in BE || head in MODALS
    val words = if (after) listOf(verb.first()) + adverbs + verb.drop(1) + rest else adverbs + verb + rest
    return words.joinToString(" ")
}

private fun conjugate(verb: String, me: Boolean): String = when (verb) {
    "is", "am", "are" -> if (me) "are" else "is"
    "was", "were" -> if (me) "were" else "was"
    "has", "have" -> if (me) "have" else "has"
    "does", "do" -> if (me) "do" else "does"
    "born" -> if (me) "were born" else "was born"
    in CONTRACTIONS -> CONTRACTIONS.getValue(verb).let { (mine, theirs) -> if (me) mine else theirs }
    in MODALS -> verb
    in COPULAR -> (if (me) "are " else "is ") + verb
    else -> if (me) FactGrammar.base(verb) else FactGrammar.thirdPerson(verb)
}

/** The relation said the other way, for a "no": `eats_meat` is "don't eat meat". Null when that can't be said plainly. */
private fun negated(adverbs: List<String>, head: String, tail: List<String>, me: Boolean): String? {
    if (tail.isEmpty() && head !in COPULAR) return null // "likes: no" says no more than that
    if (tail.lastOrNull() in FactGrammar.PREPOSITIONS) return null // "lives in: no" names no place
    if (head in CONTRACTIONS || (adverbs + tail).any { it in NEGATIONS }) return null // no double negatives
    val not = when (head) {
        "is", "am", "are" -> if (me) "aren't" else "isn't"
        "was", "were" -> if (me) "weren't" else "wasn't"
        "has", "have" -> if (me) "don't have" else "doesn't have"
        "does", "do" -> if (me) "don't" else "doesn't"
        "born" -> if (me) "weren't born" else "wasn't born"
        in MODALS -> MODALS.getValue(head)
        in COPULAR -> (if (me) "aren't " else "isn't ") + head
        else -> if (FactGrammar.isPast(head)) return null else (if (me) "don't " else "doesn't ") + FactGrammar.base(head)
    }
    return (adverbs + not + tail).joinToString(" ")
}

private fun plural(noun: String) = noun in IRREGULAR_PLURALS ||
    (noun.length > 3 && noun.endsWith('s') && !noun.endsWith("ss") && !noun.endsWith("us") && !noun.endsWith("is") && noun !in SINGULAR_WITH_S)

private val WHITESPACE = Regex("\\s+")
private val YES = setOf("true", "yes")
private val NO = setOf("false", "no")
private val ARTICLES = setOf("a", "an", "the")
private val BE = setOf("is", "am", "are", "was", "were")

/** Modals, with how each says no. */
private val MODALS = mapOf("can" to "can't", "will" to "won't", "would" to "wouldn't", "should" to "shouldn't", "could" to "couldn't")

/** Relations keep no apostrophes (`doesnt_eat`). Each is said to the user, then of someone else. */
private val CONTRACTIONS = mapOf(
    "doesnt" to ("don't" to "doesn't"), "dont" to ("don't" to "doesn't"),
    "isnt" to ("aren't" to "isn't"), "arent" to ("aren't" to "isn't"),
    "wasnt" to ("weren't" to "wasn't"), "werent" to ("weren't" to "wasn't"),
    "hasnt" to ("haven't" to "hasn't"), "havent" to ("haven't" to "hasn't"),
    "cant" to ("can't" to "can't"), "wont" to ("won't" to "won't"), "didnt" to ("didn't" to "didn't"),
)

/** Words that help or link a verb: "is", "has", "can", "doesnt". */
private val HELPERS = BE + setOf("has", "have", "does", "do") + MODALS.keys + CONTRACTIONS.keys

/** Words said with "are" or "is": "You are allergic to peanuts", "Priya is vegan". */
private val COPULAR = setOf(
    "allergic", "intolerant", "sensitive", "married", "engaged", "divorced", "related", "friends", "part", "born",
    "afraid", "scared", "interested", "fond", "good", "bad", "based", "located", "employed", "enrolled", "retired",
    "vegetarian", "vegan", "pescatarian",
)

/** Plain forms that are rarely the start of a noun, so `like` and `want_to_visit` read as verbs, but `work_address` doesn't. */
private val PLAIN_VERBS = setOf("like", "love", "enjoy", "prefer", "dislike", "hate", "want", "need", "own", "speak", "know")

/** On their own these are usually nouns: "Your plans are Japan in May", "Your reports are Maya and Raj". */
private val NOUNS_ALONE = setOf("plans", "reports")

/** Words that never change for the person, or that aren't present-tense verbs at all. */
private val FIXED = HELPERS + COPULAR

private val ADVERBS = setOf("usually", "always", "often", "sometimes", "never", "rarely", "also", "still", "only", "mostly", "regularly")
private val NEGATIONS = setOf("not", "never", "no")
private val IRREGULAR_PAST = setOf(
    "grew", "went", "met", "took", "left", "came", "became", "began", "bought", "made", "got", "gave", "saw", "knew",
    "wrote", "ran", "swam", "flew", "drove", "ate", "drank", "spent", "sold", "taught", "thought", "found", "lost", "won", "wore",
)
private val IRREGULAR_PLURALS = setOf("children", "people", "men", "women", "feet", "teeth")
private val SINGULAR_WITH_S = setOf("news", "series", "species", "alias", "lens", "physics", "maths", "politics", "economics")
