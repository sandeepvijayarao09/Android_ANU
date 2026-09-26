package io.github.sandeepvijayarao09.anu.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What speech recognition reports while it listens (ADR-043). */
sealed interface SpeechEvent {
    data object Listening : SpeechEvent

    /** How loud the user is, from 0 to 1, for the orb. */
    data class Level(val value: Float) : SpeechEvent

    data class Partial(val text: String) : SpeechEvent

    data class Final(val text: String) : SpeechEvent

    /**
     * Nothing was heard, the microphone is busy, or recognition isn't available. [heardNothing] is the
     * quiet case. [needsLanguagePack] means the on-device recognizer is missing this language's model.
     */
    data class Error(val reason: String, val heardNothing: Boolean = false, val needsLanguagePack: Boolean = false) : SpeechEvent
}

/** Speech to text, on the phone only (ADR-043). */
interface SpeechInput {
    /** False when the phone has no on-device recognizer, so voice can't be offered. */
    val available: Boolean

    /** Cold: collecting starts listening, and cancelling stops it. Ends after [SpeechEvent.Final] or [SpeechEvent.Error]. */
    fun listen(): Flow<SpeechEvent>

    /** Asks the on-device recognizer to fetch this language's model, when the user chooses to. */
    fun downloadLanguagePack() {}
}

/** Text to speech. Sentences are queued as they arrive, so Anu starts talking before the answer is finished. */
interface SpeechOutput {
    val speaking: StateFlow<Boolean>

    fun speak(sentence: String)

    /** Stops at once, for example when the user starts talking again. */
    fun stop()
}

/**
 * Finds "Hey Anu" or "Anu" at the start of what was heard, and returns what follows, which is often
 * the request itself: "Hey Anu, what's on my calendar?" (ADR-045).
 * - A bare name must be "Anu" itself, so "a new message" never wakes it.
 * - After "hey", common mishearings of the name count too.
 */
object WakePhrase {
    private val BARE = Regex("^\\s*(anu|anoo|annu)\\b[\\s,.!?:;-]*", RegexOption.IGNORE_CASE)
    private val GREETED = Regex("^\\s*(hey|hi|ok|okay)[\\s,]+(anu|anoo|annu|anew|ahnu|anou|a nu)\\b[\\s,.!?:;-]*", RegexOption.IGNORE_CASE)

    /** What followed the wake phrase, possibly empty, or null if [heard] doesn't start with it. */
    fun remainder(heard: String): String? {
        val match = GREETED.find(heard) ?: BARE.find(heard) ?: return null
        return heard.substring(match.range.last + 1).trim()
    }
}

/**
 * Cuts a streaming answer into sentences to speak. Markdown symbols are dropped, because read aloud
 * they are noise.
 */
object Sentences {
    private val END = Regex("[.!?…](?=\\s|$)|\\n")
    private val MARKUP = Regex("[*_`#>]+")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")

    /**
     * The complete sentences in [text] after [from], and where the next call should start. With
     * [final], whatever is left is a sentence too.
     */
    fun next(text: String, from: Int, final: Boolean): Pair<List<String>, Int> {
        var start = from.coerceIn(0, text.length)
        val sentences = mutableListOf<String>()
        while (true) {
            val end = END.find(text, start) ?: break
            sentences += text.substring(start, end.range.last + 1)
            start = end.range.last + 1
        }
        if (final && start < text.length) {
            sentences += text.substring(start)
            start = text.length
        }
        return sentences.map(::speakable).filter(String::isNotBlank) to start
    }

    fun speakable(text: String): String = MARKUP.replace(LINK.replace(text, "$1"), "").replace(Regex("\\s+"), " ").trim()
}
