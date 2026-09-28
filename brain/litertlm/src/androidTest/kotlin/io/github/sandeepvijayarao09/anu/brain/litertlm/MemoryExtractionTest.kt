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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Memory's fact extraction (§8.2) on a real Gemma 4 model, with made-up statements: what the user types in
 * Memory → Add becomes the fact it states. Skips itself without a model, like the S1 tests. The model's raw
 * answers are logged under AnuS1.
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
        val extractor = FactExtractor({ recording }, { LocalDate.of(2026, 9, 26) })
        val cases = listOf(
            "I'm vegetarian" to setOf("vegetarian"),
            "I'm vegan" to setOf("vegan"),
            "I'm left-handed" to setOf("left-handed"),
            "I'm vegetarian, and my sister is Priya" to setOf("vegetarian", "priya"),
        )
        for ((message, values) in cases) {
            recording.answers.clear()
            val result = extractor.extract(message)
            S1.report("extract", "\"$message\" → ${recording.answers.joinToString(" || ")}")
            val facts = (result as? FactExtractor.Result.Facts)?.facts.orEmpty()
            assertEquals(message, values, facts.map { it.value.lowercase() }.toSet())
            assertTrue("$message is about the user", facts.all { it.subject == USER })
        }
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
