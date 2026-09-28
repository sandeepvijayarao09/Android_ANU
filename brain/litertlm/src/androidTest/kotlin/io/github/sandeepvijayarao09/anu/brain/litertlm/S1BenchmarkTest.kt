package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S1: load time, time to first token, prefill and decode speed, from LiteRT-LM's own benchmark counters. */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class S1BenchmarkTest {

    /** The memory the phone reports, as sold and as the system sees it, which the Models page compares (ADR-066). */
    @Test
    fun reportsDeviceMemory() {
        val memory = ActivityManager.MemoryInfo()
        InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        S1.report("memory_total_bytes", memory.totalMem)
        if (Build.VERSION.SDK_INT >= 34) S1.report("memory_advertised_bytes", memory.advertisedMem)
    }

    @Test
    fun measuresLoadPrefillAndDecode() {
        S1.assumeModelPresent()
        benchmark(prefix = "")
    }

    /**
     * Gemma 4's files carry multi-token prediction heads, which LiteRT-LM can use to decode several tokens a step
     * (speculative decoding). This reads what the file says it supports, then loads it with the runtime's default,
     * with speculative decoding off, and on, reads the same prompts greedily each time, and reports each run's
     * speed and whether the answers match.
     */
    @Test
    fun measuresSpeculativeDecoding() {
        S1.assumeModelPresent()
        Capabilities(S1.modelPath).use {
            S1.report("speculative_support", it.hasSpeculativeDecodingSupport())
            S1.report("thinking_support", it.supportsThinking())
            S1.report("function_calling_support", it.supportsFunctionCalling())
            S1.report("modalities", it.inputModalities())
            S1.report("default_sampler", it.defaultSamplerParams())
        }
        // The order can be changed (`modes=on+off`), so a phone warming up doesn't favour whichever runs first.
        val flags = mapOf("default" to null, "off" to false, "on" to true)
        val modes = (InstrumentationRegistry.getArguments().getString("modes") ?: "default+off+on").split('+').filter(flags::containsKey)
        val answers = modes.associateWith { label -> benchmark(prefix = "${label}_", speculative = flags.getValue(label)) }
        if ("default" in answers && "off" in answers) S1.report("default_matches_off", answers["default"] == answers["off"])
        if ("on" in answers && "off" in answers) S1.report("on_matches_off", answers["on"] == answers["off"])
        answers.forEach { (label, texts) -> texts.forEachIndexed { i, text -> S1.report("${label}_answer_$i", text.take(160).replace('\n', ' ')) } }
        assertTrue("every run answered", answers.values.flatten().all(String::isNotBlank))
    }

    /** Loads the model with [speculative] as the runtime flag (null leaves its default), and runs both prompts. */
    private fun benchmark(prefix: String, speculative: Boolean? = null): List<String> {
        val previous = ExperimentalFlags.enableSpeculativeDecoding
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = speculative
        try {
            val start = SystemClock.elapsedRealtime()
            Engine(EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())).use { engine ->
                engine.initialize()
                S1.report("${prefix}load_ms", SystemClock.elapsedRealtime() - start)
                return listOf(
                    run(engine, "${prefix}short", "Explain in about 120 words why running AI on a phone helps privacy."),
                    run(engine, "${prefix}long", LONG_CONTEXT + "\n\nSummarize the notes above in three bullet points."),
                )
            }
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = previous
            ExperimentalFlags.enableBenchmark = false
        }
    }

    private fun run(engine: Engine, label: String, prompt: String): String {
        val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
        engine.createConversation(config).use { conversation ->
            val answer = conversation.sendMessage(prompt).text()
            val info = conversation.getBenchmarkInfo()
            S1.report("${label}_ttft_ms", (info.timeToFirstTokenInSecond * 1000).toInt())
            S1.report("${label}_prefill_tokens", info.lastPrefillTokenCount)
            S1.report("${label}_prefill_tok_s", "%.1f".format(info.lastPrefillTokensPerSecond))
            S1.report("${label}_decode_tokens", info.lastDecodeTokenCount)
            S1.report("${label}_decode_tok_s", "%.1f".format(info.lastDecodeTokensPerSecond))
            return answer
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
