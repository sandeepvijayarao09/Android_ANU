package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.memory.Extraction
import io.github.sandeepvijayarao09.anu.core.memory.FactExtractor
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.JsonSchemas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Constraint probe (ADR-123): does the runtime's JSON constraint still change what memory's extractor keeps? Each
 * made-up message goes through the real [FactExtractor] twice: as built, with a free first answer, and with every
 * answer held to the schema, as before ADR-123. It logs what each kept under AnuS1 and passes either way: it
 * measures. Run it after a LiteRT-LM upgrade: if the two agree, the first answer could be held to the schema again.
 */
@RunWith(AndroidJUnit4::class)
class ExtractionConstraintProbeTest {

    /** Holds every answer to the extraction schema, as extraction did before ADR-123. */
    private class Constrained(private val inner: Brain) : Brain by inner {
        override fun stream(request: BrainRequest): Flow<BrainEvent> = inner.stream(request.copy(responseSchema = SCHEMA))
    }

    @Test
    fun freeAndConstrained() = runBlocking {
        for (day in listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 29))) {
            val extractors = listOf("free" to FactExtractor({ brain }, { day }), "constrained" to FactExtractor({ Constrained(brain) }, { day }))
            for (message in MESSAGES) {
                for ((label, extractor) in extractors) {
                    val kept = when (val result = extractor.extract(message)) {
                        is FactExtractor.Result.Facts -> result.facts.joinToString("; ") { "${it.subject} ${it.predicate}: ${it.value}" }.ifEmpty { "nothing" }
                        else -> result.toString()
                    }
                    S1.report("constraint", "$label $day \"$message\" → $kept")
                }
            }
        }
    }

    companion object {
        private val SCHEMA = JsonSchemas.of(Extraction.serializer())

        private val MESSAGES = listOf(
            // Facts told with a request: each states one or two lasting facts.
            "By the way, I'm vegetarian, so please keep that in mind whenever you suggest recipes or restaurants.",
            "Also, I usually cook for two people, so please keep that in mind whenever you suggest recipes or portion sizes.",
            "Just so you know, I don't drink alcohol, so leave wine out of any suggestions.",
            "FYI my daughter Ana is allergic to nuts, so please remember that when we plan meals.",
            "I work night shifts at the hospital, so don't book anything for me before noon.",
            "I'm vegetarian, so suggest recipes and restaurants that fit.",
            // Requests: nothing lasting.
            "Can you find me a vegetarian restaurant for tonight?",
            "What should I cook for my friends this weekend?",
        )

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
