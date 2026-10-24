package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.agent.BaseOpinion
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.eval.RouterEval
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Base's own opinion (ADR-106) on the router eval's two splits, made-up people only: the app's prompt and parser
 * ([BaseOpinion]), one conversation each, scored as Base or anything heavier, beside the rule-based router counted the
 * same way and the two combined (Pro when either says so, as the app does). The prompt was written from the dev
 * split's rubric; the held-out split is scored apart. Logged as `opinion_*` under AnuS1. Runner arguments: backend and
 * modelPath (S1).
 */
@RunWith(AndroidJUnit4::class)
class BaseOpinionEvalTest {

    @Test
    fun scoresBasesOpinion() = runBlocking {
        S1.assumeModelPresent()
        EngineHolder(EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir()), S1.options()).use { holder ->
            val brain = LiteRtLmBrain(
                id = BrainId.BASE,
                capabilities = BrainCapabilities(BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true),
                engines = holder,
            )
            val opinion = BaseOpinion.of(brain::once)
            for (split in listOf("dev", "heldout")) {
                val cases = RouterEval.load(split)
                val rules = RouterEval.score(cases).misses.associate { it.case.request to it.routed }
                var alone = 0
                var ruled = 0
                var combined = 0
                var nanos = 0L
                for (case in cases) {
                    val heavy = case.tier != Tier.BASE
                    val started = System.nanoTime()
                    val says = opinion.beyondBase(case.request)
                    nanos += System.nanoTime() - started
                    val rulesHeavy = (rules[case.request] ?: case.tier) != Tier.BASE
                    if ((says != false) == heavy) alone++
                    if (rulesHeavy == heavy) ruled++
                    if ((rulesHeavy || says == true) == heavy) combined++
                    val mark = if ((rulesHeavy || says == true) == heavy) "ok" else "MISS"
                    S1.report("opinion_case", "$split $mark ${case.tier} base=${says ?: "none"} rules=${if (rulesHeavy) "PRO" else "BASE"}: ${case.request.take(60)}")
                }
                S1.report("opinion_$split", "base alone $alone, rules $ruled, either $combined of ${cases.size}, ${nanos / cases.size / 1_000_000} ms each")
            }
            brain.reset()
        }
    }
}
