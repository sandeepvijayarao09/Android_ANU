package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.memory.FactExtractor
import io.github.sandeepvijayarao09.anu.core.memory.USER
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Memory's fact extraction (§8.2) on a real Gemma 4 model, with made-up statements: what the user types in
 * Memory → Add, or says in passing in chat, becomes the fact it states, and a request keeps nothing. Each runs on
 * two days, since a different date line once flipped greedy answers (ADR-123). Skips itself without a model, like
 * the S1 tests. The model's raw answers are logged under AnuS1.
 */
@RunWith(AndroidJUnit4::class)
class MemoryExtractionTest {

    /** Keeps the model's raw answers, to see what extraction was given. */
    private class Recording(private val inner: Brain) : Brain by inner {
        val answers = mutableListOf<String>()

        override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
            val text = StringBuilder()
            inner.stream(request).collect { event ->
                if (event is BrainEvent.TextDelta) text.append(event.text)
                emit(event)
            }
            answers += text.toString()
        }
    }

    @Test
    fun statementsBecomeTheirFacts() = runBlocking {
        val recording = Recording(brain)
        // Each message, and the values its facts should have: one pattern per fact, over the value in lowercase.
        val cases = listOf(
            "I'm vegetarian" to listOf("vegetarian"),
            "I'm vegan" to listOf("vegan"),
            "I'm left-handed" to listOf("left-handed"),
            "I'm vegetarian, and my sister is Priya" to listOf("vegetarian", "priya"),
            // Plain chat on anu_api36 (2026-09-29): a fact told in passing, with a request after it.
            "By the way, I'm vegetarian, so please keep that in mind whenever you suggest recipes or restaurants." to listOf("vegetarian"),
            "Also, I usually cook for two people, so please keep that in mind whenever you suggest recipes or portion sizes." to
                listOf("(cooks? )?(for )?(two|2)( people)?"),
            // Requests, questions and plans: nothing lasting.
            "Can you remind me to buy milk tomorrow?" to emptyList(),
            "I need a quick recipe for dinner tonight." to emptyList(),
            "Can you find me a vegetarian restaurant for tonight?" to emptyList(),
            "What should I cook for my friends this weekend?" to emptyList(),
        )
        // Every case runs and is logged before the test fails, so one run shows all of the model's answers.
        val wrong = mutableListOf<String>()
        for (day in listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 29))) {
            val extractor = FactExtractor({ recording }, { day })
            for ((message, patterns) in cases) {
                recording.answers.clear()
                val result = extractor.extract(message)
                // A free answer may be laid out on several lines; one line each keeps the log readable.
                S1.report("extract", "$day \"$message\" → ${recording.answers.joinToString(" || ") { it.replace('\n', ' ') }}")
                val facts = (result as? FactExtractor.Result.Facts)?.facts.orEmpty()
                val values = facts.map { it.value.lowercase() }
                val right = values.size == patterns.size && patterns.all { pattern -> values.any(Regex(pattern)::matches) }
                if (!right || !facts.all { it.subject == USER }) {
                    wrong += "$day \"$message\" kept ${facts.map { "${it.subject} ${it.predicate}: ${it.value}" }}"
                }
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    companion object {
        private lateinit var holder: EngineHolder
        private lateinit var brain: LiteRtLmBrain

        @JvmStatic
        @BeforeClass
        fun setUp() {
            S1.assumeModelPresent()
            holder = EngineHolder(EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir()), S1.options())
            brain = LiteRtLmBrain(
                id = BrainId.PRO,
                capabilities = BrainCapabilities(BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true),
                engines = holder,
            )
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::brain.isInitialized) runBlocking { brain.reset() }
            if (::holder.isInitialized) holder.close()
        }
    }
}
