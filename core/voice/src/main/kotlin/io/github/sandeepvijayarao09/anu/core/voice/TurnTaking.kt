package io.github.sandeepvijayarao09.anu.core.voice

import kotlinx.coroutines.flow.Flow

/**
 * When the user has finished speaking (ADR-129). The recognizer was asked for 2 s of silence before a sentence ends
 * (ADR-101), which put 2 s between every request and its answer. This ends a listen sooner when what was heard
 * sounds finished, and waits the long pause only when it doesn't, as Google's assistant does:
 * - After [quickMs] of quiet, when the words so far end like a sentence ("…on my calendar") and are three or more,
 *   or are a whole one-word answer ("yes").
 * - After [MID_MS], when two words end like one ("call Maya"), since a thought often goes on after two.
 * - After [slowMs], when they end mid-thought ("…remind me to", "um") or are one word that isn't a whole answer.
 * The word rules are English's. In another [language] nothing can be told from the words, so every turn waits [slowMs],
 * still sooner than the recognizer's 2 s.
 * Quiet is measured from the later of the last loud level and the last change in the words, so a recognizer that
 * reports no levels still ends on its words. Nothing ends before a word is heard; the recognizer's own timeout covers
 * a silent listen.
 */
class Endpointer(
    private val quickMs: Long = QUICK_MS,
    private val slowMs: Long = SLOW_MS,
    /** The recognizer's language, as an ISO 639 code ("en", "eng", "hi"). */
    private val language: String = "en",
) {
    private var floor = 1f
    private var lastActive = -1L
    private var words = ""

    /** The user's loudness from 0 to 1, as [SpeechEvent.Level] gives it, at [now] in milliseconds. */
    fun level(value: Float, now: Long) {
        floor = minOf(floor, value)
        if (value >= floor + LOUD_ABOVE_FLOOR && words.isNotBlank()) lastActive = maxOf(lastActive, now)
    }

    /** The words heard so far. */
    fun partial(text: String, now: Long) {
        if (text.isBlank() || text == words) return
        words = text
        lastActive = maxOf(lastActive, now)
    }

    /** When the user last spoke, or -1 before any word. */
    val spokeAt: Long get() = lastActive

    /** Whether the listen can end at [now]. */
    fun done(now: Long): Boolean = lastActive >= 0 && now - lastActive >= wait()

    /** How long a pause ends this turn, for what has been heard so far. */
    fun wait(): Long = waitFor(words, quickMs, slowMs, language)

    enum class Sounds { FINISHED, UNFINISHED }

    companion object {
        const val QUICK_MS = 700L
        const val MID_MS = 1_100L
        const val SLOW_MS = 1_800L

        /** Whether the word rules know [language]: English only, for now. */
        fun knows(language: String): Boolean = language.lowercase() in ENGLISH

        private val ENGLISH = setOf("en", "eng")

        /** The pause that ends a turn after [heard], in [language]. */
        fun waitFor(heard: String, quickMs: Long = QUICK_MS, slowMs: Long = SLOW_MS, language: String = "en"): Long = when {
            !knows(language) -> slowMs
            sounds(heard) == Sounds.UNFINISHED -> slowMs
            words(heard).size == 2 -> maxOf(quickMs, MID_MS)
            else -> quickMs
        }

        private fun words(heard: String) = heard.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}' ]"), " ").trim().split(Regex("\\s+")).filter(String::isNotEmpty)

        /** 0.15 is about 9 dB above the quietest level so far on the recognizer's scale. */
        private const val LOUD_ABOVE_FLOOR = 0.15f

        /** Words that leave a sentence hanging when they come last. */
        private val HANGING = setOf(
            "and", "or", "but", "so", "because", "if", "then", "which", "when", "where", "while",
            "the", "a", "an", "my", "your", "his", "her", "their", "our", "its",
            "to", "of", "for", "with", "at", "on", "in", "into", "about", "from", "by", "as", "than",
            "is", "are", "was", "were", "what's", "whats", "what", "how", "i", "i'm", "um", "uh", "er", "erm", "hmm",
            "also", "plus", "let's", "between", "not", "more", "many", "much", "every", "each", "some", "any", "whose",
        )

        /** Two last words that leave it hanging, though either can end a sentence alone. */
        private val HANGING_PAIRS = setOf(
            "tell me", "give me", "remind me", "show me", "send me", "let me", "get me", "find me", "ask me",
            "what does", "how does", "when does", "where does", "why does", "what do", "how do", "where do", "why do", "when do",
            "how many", "how much", "what time",
        )

        /** One-word turns that are whole answers. */
        private val WHOLE = setOf(
            "yes", "no", "yeah", "yep", "nope", "ok", "okay", "sure", "stop", "thanks", "cancel", "done", "send",
            "hi", "hello", "bye", "goodbye", "right", "correct", "exactly", "nothing", "never", "later", "now",
        )

        fun sounds(heard: String): Sounds {
            val words = words(heard)
            if (words.isEmpty()) return Sounds.UNFINISHED
            if (words.size == 1) return if (words[0] in WHOLE) Sounds.FINISHED else Sounds.UNFINISHED
            val pair = words.takeLast(2).joinToString(" ")
            return if (words.last() in HANGING || pair in HANGING_PAIRS) Sounds.UNFINISHED else Sounds.FINISHED
        }
    }
}

/**
 * Talking over Anu while it speaks (ADR-129): emits when the user starts talking, so live talk stops the speech and
 * listens. Cold: collecting opens the microphone, and cancelling closes it.
 */
fun interface BargeIn {
    fun watch(): Flow<Unit>
}

/**
 * Whether the user is talking over Anu's own voice (ADR-129), from the echo-cancelled microphone's loudness, one
 * [CHUNK]-sample (20 ms) chunk at a time.
 * - The first [calibrationChunks] chunks (1 s, which covers the engine starting to talk) learn how loud Anu's own voice
 *   still is after echo cancellation: their 90th-percentile loudness, plus [MARGIN].
 * - Then [hold] of the last [window] chunks above it is the user. A word of Anu's own that's louder than usual is
 *   shorter than that, and where the phone can't cancel its echo, the bar sits above its own voice, so it fails quiet:
 *   no barge-in rather than a false one.
 */
class BargeInDetector(
    private val calibrationChunks: Int = 50,
    private val window: Int = 20,
    private val hold: Int = 16,
) {
    private val calibration = ArrayList<Float>(calibrationChunks)
    private val recent = ArrayDeque<Boolean>()

    /** The loudness the user must pass, once calibrated, else null. */
    var threshold: Float? = null
        private set

    /** Loudness of Anu's own voice heard back, once calibrated. */
    var echo: Float = 0f
        private set

    /** Takes one chunk's [level] and says whether the user is talking now. */
    fun push(level: Float): Boolean {
        val bar = threshold
        if (bar == null) {
            calibration += level
            if (calibration.size >= calibrationChunks) {
                echo = calibration.sorted()[(calibration.size * 9) / 10]
                threshold = maxOf(echo + MARGIN, MIN_LEVEL)
            }
            return false
        }
        recent.addLast(level >= bar)
        if (recent.size > window) recent.removeFirst()
        return recent.count { it } >= hold
    }

    companion object {
        /** 20 ms at 16 kHz. */
        const val CHUNK = 320

        /** 0.1 is 6 dB on [level]'s scale. */
        const val MARGIN = 0.1f

        /** About -33 dBFS: quieter than that is the room, not someone talking to the phone. */
        const val MIN_LEVEL = 0.45f
    }
}

/** An installed text-to-speech voice, as [VoicePick] compares them. */
data class TtsVoice(
    val name: String,
    val language: String,
    val country: String,
    /** Android's `Voice.QUALITY_*`: 100 very low to 500 very high. */
    val quality: Int,
    /** Android's `Voice.LATENCY_*`: 100 very low to 500 very high. */
    val latency: Int,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

/**
 * The voice Anu speaks with (ADR-129): one on the phone, never one that needs the network (I3: the words would leave
 * it), in the user's language, the country's own first, then the clearest, then the quickest to start.
 */
object VoicePick {
    fun best(voices: List<TtsVoice>, language: String, country: String): TtsVoice? = voices
        .filter { !it.needsNetwork && it.installed && it.language.equals(language, ignoreCase = true) }
        .sortedWith(
            compareByDescending<TtsVoice> { it.country.equals(country, ignoreCase = true) }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenBy { it.name },
        )
        .firstOrNull()
}
