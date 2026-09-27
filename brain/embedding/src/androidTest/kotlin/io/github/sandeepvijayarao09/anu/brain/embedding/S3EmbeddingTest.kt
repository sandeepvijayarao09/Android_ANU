package io.github.sandeepvijayarao09.anu.brain.embedding

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandeepvijayarao09.anu.core.memory.FactExtractor
import io.github.sandeepvijayarao09.anu.core.memory.InMemoryMemoryStore
import io.github.sandeepvijayarao09.anu.core.memory.MemoryPipeline
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import io.github.sandeepvijayarao09.anu.core.memory.USER
import io.github.sandeepvijayarao09.anu.core.memory.Vectors
import io.github.sandeepvijayarao09.anu.core.memory.factText
import io.github.sandeepvijayarao09.anu.core.model.Embedder
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.system.measureNanoTime

/**
 * Spike S3's eval (ADR-055): EmbeddingGemma's speed on this device, and which facts search by meaning
 * brings to a message, through memory's own path (indexing, the query, the decision rule in Relevance).
 * Push the two files once, then run:
 *
 *   adb push embeddinggemma-300M_seq512_mixed-precision.tflite sentencepiece.model /data/local/tmp/anu/models/
 *   ANDROID_SERIAL=<serial> ./gradlew :brain:embedding:connectedDebugAndroidTest
 *   adb logcat -d -s AnuS3
 *
 * It skips itself when the files are missing. The facts are made up; each message shares no word with
 * the facts it should find. The log ends with every message's closeness to every fact, for retuning the
 * rule for another model.
 */
@RunWith(AndroidJUnit4::class)
class S3EmbeddingTest {
    private val dir = File(InstrumentationRegistry.getArguments().getString("embeddingDir") ?: "/data/local/tmp/anu/models")

    private data class Known(val subject: String, val relation: String, val value: String) {
        val text get() = factText(subject, relation, value)
    }

    private val diet = Known(USER, "diet", "vegetarian")
    private val allergy = Known(USER, "allergy", "peanuts")
    private val sister = Known(USER, "sister", "Priya")
    private val priya = Known("Priya", "lives_in", "Pune")
    private val manager = Known(USER, "manager", "Maya Chen")
    private val birthday = Known("Maya Chen", "birthday", "12 March")
    private val home = Known(USER, "home_city", "Boston")
    private val meetings = Known(USER, "meeting_preference", "mornings")
    private val dog = Known(USER, "dog", "Bruno")
    private val car = Known(USER, "car", "Honda Civic")
    private val sport = Known(USER, "favorite_sport", "tennis")
    private val employer = Known(USER, "employer", "Acme Robotics")
    private val facts = listOf(diet, allergy, sister, priya, manager, birthday, home, meetings, dog, car, sport, employer)

    /** A message, and the facts it should find by meaning. */
    private val questions = listOf(
        "Plan a dinner for tonight" to setOf(diet),
        "Suggest a restaurant for Saturday" to setOf(diet, allergy),
        "Can I eat this trail mix?" to setOf(allergy),
        "Book a flight home for the holidays" to setOf(home),
        "When should I put the design review?" to setOf(meetings),
        "Get a present for my boss" to setOf(manager),
        "Buy a new toy for the puppy" to setOf(dog),
        "The engine is making a strange noise" to setOf(car),
        "Find a court to play this weekend" to setOf(sport),
        "Write my out-of-office for the company" to setOf(employer),
        "Call my sibling" to setOf(sister),
    )

    /** Messages that should bring no fact. */
    private val smallTalk = listOf(
        "Hi, how are you?", "Thanks!", "What time is it?", "Tell me a joke", "OK", "Good morning", "What's the weather like?",
        "Translate hello into French", "What is 12 times 7?", "Who won the game last night?", "Summarize this article",
        "Set a timer for ten minutes",
    )

    @Test
    fun speedAndRetrieval() = runBlocking {
        val model = File(dir, EmbeddingCatalog.MODEL.name)
        val tokenizer = File(dir, EmbeddingCatalog.TOKENIZER.name)
        assumeTrue("No EmbeddingGemma in $dir; skipping", model.canRead() && tokenizer.canRead())
        val embedder = GemmaEmbedder(model, tokenizer)

        val load = millis { embedder.embed(listOf("warm up"), EmbeddingKind.QUERY) }
        report("load and first query: $load ms")

        // Memory learns the facts before the model is installed, then indexes them all, as after an install.
        var installed: Embedder? = null
        val store = InMemoryMemoryStore()
        val memory = MemoryPipeline(store, FactExtractor({ null }), embedder = { installed })
        facts.forEach { memory.remember(it.subject, it.relation, it.value, ProfileSection.PREFERENCES, sensitive = false, ref = null) }
        installed = embedder
        var indexed = 0
        val indexing = millis { indexed = memory.index() }
        assertEquals(facts.size, indexed)
        report("indexed ${facts.size} facts: $indexing ms (${indexing / facts.size} ms each)")
        val vectors = store.vectors(embedder.model)
        assertEquals(EmbeddingCatalog.DIMENSIONS, vectors.values.first().size)
        assertEquals(1.0, vectors.values.first().sumOf { (it * it).toDouble() }, 1e-3)

        val times = mutableListOf<Long>()
        var found = 0
        var wrong = 0
        for ((question, wanted) in questions) {
            lateinit var picked: List<String>
            times += millis { picked = memory.relevant(question).map { it.text } }
            found += wanted.count { it.text in picked }
            wrong += picked.count { text -> wanted.none { it.text == text } }
            report("\"$question\" → ${picked.ifEmpty { listOf("nothing") }.joinToString(" · ")}")
        }
        val chatty = smallTalk.filter { text ->
            val picked = memory.relevant(text).map { it.text }
            if (picked.isNotEmpty()) report("small talk \"$text\" → ${picked.joinToString(" · ")}")
            picked.isNotEmpty()
        }
        val expected = questions.sumOf { it.second.size }
        val median = times.sorted()[times.size / 2]
        report("search per message: median $median ms, max ${times.max()} ms")
        report("found $found of $expected facts, $wrong wrong in ${questions.size} messages; small talk with facts: ${chatty.size} of ${smallTalk.size}")

        // Every message's closeness to every fact, for retuning Relevance offline.
        val ids = store.activeFacts().associate { it.id to it.text }
        report("FACTS|" + ids.values.joinToString("|"))
        for (text in questions.map { it.first } + smallTalk) {
            val query = embedder.embed(listOf(text), EmbeddingKind.QUERY).single()
            report("ROW|$text|" + ids.keys.joinToString(",") { "%.4f".format(Vectors.cosine(query, vectors.getValue(it))) })
        }

        // The same text gives the same vector.
        val first = embedder.embed(listOf(questions.first().first), EmbeddingKind.QUERY).single()
        val again = embedder.embed(listOf(questions.first().first), EmbeddingKind.QUERY).single()
        assertEquals(0f, first.indices.maxOf { abs(again[it] - first[it]) }, 1e-5f)

        // S3's bar (ADR-055).
        assertTrue("found $found of $expected", found * 10 >= expected * 8)
        assertTrue("$wrong wrong facts", wrong * 2 <= questions.size)
        assertTrue("small talk brought facts: $chatty", chatty.size <= 1)
        // Speed is reported, not held to a bar: on the emulator it varies 4× with how the host schedules it
        // (454 ms in front, about 2 s behind other windows). This only catches a broken setup.
        assertTrue("search took $median ms", median < 10_000)
    }

    private inline fun millis(block: () -> Unit): Long = measureNanoTime(block) / 1_000_000

    private fun report(line: String) {
        Log.i("AnuS3", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("AnuS3", line) })
    }
}
