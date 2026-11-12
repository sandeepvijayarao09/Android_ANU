package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.eval.MemoryEval
import io.github.sandeepvijayarao09.anu.eval.MemoryEvalSet
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The memory eval's device tier (§15, ADR-143): the JVM tier's made-up lives, with extraction by a real Gemma model on
 * the phone instead of the scripted, perfect one. Facts kept, extra facts, contradictions and leaks then measure
 * extraction and memory together. Search by meaning isn't loaded here, so its questions are skipped, as on the JVM.
 * It reports and doesn't fail: read the `memory_eval` lines of `adb logcat -d -s AnuS1`. Skips itself without a model.
 */
@RunWith(AndroidJUnit4::class)
class MemoryEvalTest {
    @Test
    fun scoresMemoryWithRealExtraction() {
        for ((name, set) in listOf("dev" to MemoryEvalSet.dev, "held out" to MemoryEvalSet.heldOut)) {
            val score = MemoryEval.run(set, brain = { brain })
            score.report("$name, ${S1.modelPath.substringAfterLast('/')} on ${S1.backendName}").lines()
                .filter { it.isNotBlank() }
                .forEach { S1.report("memory_eval", it) }
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
