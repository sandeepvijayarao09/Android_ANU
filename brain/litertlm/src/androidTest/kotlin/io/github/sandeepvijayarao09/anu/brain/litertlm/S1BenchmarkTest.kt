package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Test
import org.junit.runner.RunWith

/** S1: load time, time to first token, prefill and decode speed, from LiteRT-LM's own benchmark counters. */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class S1BenchmarkTest {

    @Test
    fun measuresLoadPrefillAndDecode() {
        S1.assumeModelPresent()
        ExperimentalFlags.enableBenchmark = true
        try {
            val start = SystemClock.elapsedRealtime()
            Engine(EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())).use { engine ->
                engine.initialize()
                S1.report("load_ms", SystemClock.elapsedRealtime() - start)
                run(engine, "short", "Explain in about 120 words why running AI on a phone helps privacy.")
                run(engine, "long", LONG_CONTEXT + "\n\nSummarize the notes above in three bullet points.")
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
        }
    }

    private fun run(engine: Engine, label: String, prompt: String) {
        val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
        engine.createConversation(config).use { conversation ->
            conversation.sendMessage(prompt)
            val info = conversation.getBenchmarkInfo()
            S1.report("${label}_ttft_ms", (info.timeToFirstTokenInSecond * 1000).toInt())
            S1.report("${label}_prefill_tokens", info.lastPrefillTokenCount)
            S1.report("${label}_prefill_tok_s", "%.1f".format(info.lastPrefillTokensPerSecond))
            S1.report("${label}_decode_tokens", info.lastDecodeTokenCount)
            S1.report("${label}_decode_tok_s", "%.1f".format(info.lastDecodeTokensPerSecond))
        }
    }

    private companion object {
        // About 1,000 tokens of meeting notes, to measure prefill on a realistic email-sized context.
        val LONG_CONTEXT = List(12) { i ->
            "Meeting note ${i + 1}: The team reviewed the launch plan for the calendar assistant, " +
                "agreed that invites must always be approved by the user, moved the design review to " +
                "Thursday afternoon, and asked Maya to send the updated budget by Friday. Open questions " +
                "remain about notification access on sideloaded builds and battery use during overnight " +
                "memory consolidation."
        }.joinToString("\n")
    }
}
