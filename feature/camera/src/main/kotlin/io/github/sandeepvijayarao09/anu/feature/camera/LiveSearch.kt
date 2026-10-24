package io.github.sandeepvijayarao09.anu.feature.camera

import io.github.sandeepvijayarao09.anu.core.model.DetectorLabel
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import io.github.sandeepvijayarao09.anu.core.model.VisualEmbedder
import io.github.sandeepvijayarao09.anu.core.model.closeness
import java.util.concurrent.ConcurrentHashMap

/** How a thing in view matched what the user asked for (ADR-111). */
enum class MatchedBy {
    /** A word of the question is in the label or its meaning: no model needed. */
    WORDS,

    /** The label YOLOX gave it means what the question asks for, by EmbeddingGemma 2. */
    LABEL,

    /** Its own picture, which EmbeddingGemma 2 names among the labels, shows what the question asks for. */
    PICTURE,
}

/**
 * Matches a question such as "anything to drink" to the things in view (ADR-111):
 * - by meaning, with EmbeddingGemma 2: the question's vector against each label's, embedded once. The labels that
 *   stand well clear of the rest match ("anything to drink": bottle, wine glass and cup);
 * - by picture: EmbeddingGemma 2 also names each thing's crop among the labels, which catches YOLOX's mistakes
 *   (a cup it calls a bowl). Comparing a crop with the question directly didn't separate drinks from tables and
 *   couches in S14, so pictures are named, not scored against the question;
 * - by words, without EmbeddingGemma 2: words shared with a label or its meaning ("drink" is in a bottle's).
 * Thresholds were set with EmbeddingGemma 2 on real photos (S14, LiveSearchEvalTest).
 */
class LiveSearch(
    val labels: List<DetectorLabel>,
    private val floor: Float = LABEL_FLOOR,
    private val aboveMedian: Float = LABEL_ABOVE_MEDIAN,
    private val margin: Float = LABEL_MARGIN,
) {
    /** What each label is embedded as. */
    val labelTexts: List<String> = labels.map { "${it.name}: ${it.meaning}" }

    private val labelWords: List<Set<String>> = labels.map { words(it.name + " " + it.meaning) }

    /** The labels sharing a word with [question], beyond small words such as "a", "to" and "something". */
    fun wordMatches(question: String): Set<Int> {
        val asked = words(question)
        if (asked.isEmpty()) return emptySet()
        return labelWords.indices.filterTo(HashSet()) { i -> labelWords[i].any { it in asked } }
    }

    /**
     * The labels close enough in meaning, from their closeness to the question: at least [LABEL_FLOOR], at least
     * [LABEL_ABOVE_MEDIAN] above the middle label's, and within [LABEL_MARGIN] of the closest. EmbeddingGemma 2's
     * closeness runs high for every label (0.68 to 0.75 in the middle), so the distance from the rest tells most;
     * the floor keeps a question about something none of the labels are ("my keys", closest to keyboard at 0.80)
     * from matching anything.
     */
    fun labelMatches(scores: FloatArray): Set<Int> {
        if (scores.isEmpty()) return emptySet()
        val best = scores.max()
        val median = scores.sorted()[scores.size / 2]
        return scores.indices.filterTo(HashSet()) {
            scores[it] >= floor && scores[it] >= median + aboveMedian && scores[it] >= best - margin
        }
    }

    /** The label a picture shows most, from its closeness to each label's vector. */
    fun pictureLabel(scores: FloatArray): Int = scores.indices.maxBy { scores[it] }

    companion object {
        const val LABEL_FLOOR = 0.82f
        const val LABEL_ABOVE_MEDIAN = 0.095f
        const val LABEL_MARGIN = 0.06f

        private val SMALL = setOf(
            "a", "an", "the", "to", "of", "for", "and", "or", "in", "on", "at", "with", "my", "me", "i", "is", "are",
            "any", "anything", "something", "some", "thing", "things", "show", "find", "where", "what", "there", "here",
            "it", "this", "that", "can", "you", "see", "look", "looking", "around", "near", "please",
        )

        /** The words of [text] that carry meaning, in lower case, with a plural's "s" dropped. */
        fun words(text: String): Set<String> = text.lowercase().split(Regex("[^a-z]+"))
            .filter { it.length > 1 && it !in SMALL }
            .mapTo(HashSet()) { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
    }
}

/**
 * The search as the camera runs it (ADR-111): the question's matching labels, the labels' vectors (made once per
 * embedder), and the label each followed thing's picture shows. Without EmbeddingGemma 2 it matches by words.
 * [ask], [look] and [prepare] run on one thread; the camera's thread reads [match] and [needsPicture].
 */
class Finder(
    private val search: LiveSearch,
    private val embedder: () -> VisualEmbedder?,
    /** Keeps the labels' vectors between visits: they're the same every time, and take seconds to make. */
    private val store: LabelVectors? = null,
) {
    private var labelVectors: List<FloatArray>? = null
    private var labelModel: String? = null
    // The camera's thread reads these while the search's thread asks and looks.
    @Volatile private var question = ""
    @Volatile private var byWords: Set<Int> = emptySet()
    @Volatile private var byMeaning: Set<Int>? = null
    private val pictures = ConcurrentHashMap<Long, Int>()

    /** Whether meaning and pictures can be used: EmbeddingGemma 2 is installed. */
    val knowsMeaning: Boolean get() = embedder() != null

    val asking: Boolean get() = question.isNotBlank()

    /** The labels the question matches, by meaning once that's known, else by words. */
    val matching: Set<Int> get() = byMeaning ?: byWords

    /** Takes a new question: its words at once, then its meaning if EmbeddingGemma 2 is installed. */
    suspend fun ask(text: String) {
        val asked = text.trim()
        question = asked
        byMeaning = null
        byWords = search.wordMatches(asked)
        if (asked.isEmpty()) return
        val model = embedder() ?: return
        val labels = labelVectors(model)
        val vector = model.embed(listOf(asked), EmbeddingKind.QUERY).single()
        if (question == asked) byMeaning = search.labelMatches(FloatArray(labels.size) { closeness(vector, labels[it]) })
    }

    /** Whether thing [id]'s picture is still to be named. */
    fun needsPicture(id: Long): Boolean = asking && knowsMeaning && !pictures.containsKey(id)

    /** Names the picture of thing [id] among the labels, and returns that label. */
    suspend fun look(id: Long, jpeg: ByteArray): Int? {
        val model = embedder() ?: return null
        val labels = labelVectors(model)
        val picture = model.embedImages(listOf(jpeg)).single()
        return search.pictureLabel(FloatArray(labels.size) { closeness(picture, labels[it]) }).also { pictures[id] = it }
    }

    /** Forgets the pictures of things no longer in view. */
    fun keepOnly(ids: Set<Long>) {
        pictures.keys.retainAll(ids)
    }

    /** How the thing [id] that YOLOX calls [label] matches, or null if it doesn't, or nothing is asked. */
    fun match(id: Long, label: Int): MatchedBy? {
        if (!asking) return null
        val meaning = byMeaning
        if (meaning == null) return if (label in byWords) MatchedBy.WORDS else null
        return when {
            label in meaning -> MatchedBy.LABEL
            pictures[id]?.let { it in meaning } == true -> MatchedBy.PICTURE
            else -> null
        }
    }

    /** Makes the labels' vectors ahead of the first question, so it's answered at once. */
    suspend fun prepare() {
        embedder()?.let { labelVectors(it) }
    }

    private suspend fun labelVectors(model: VisualEmbedder): List<FloatArray> {
        labelVectors?.takeIf { labelModel == model.model }?.let { return it }
        val kept = store?.load(model.model, search.labelTexts)
        return (kept ?: model.embed(search.labelTexts, EmbeddingKind.DOCUMENT).also { store?.save(model.model, search.labelTexts, it) }).also {
            labelVectors = it
            labelModel = model.model
        }
    }
}

/** Where the labels' vectors are kept for a model, if they were made from these same texts. */
interface LabelVectors {
    fun load(model: String, texts: List<String>): List<FloatArray>?

    fun save(model: String, texts: List<String>, vectors: List<FloatArray>)
}
