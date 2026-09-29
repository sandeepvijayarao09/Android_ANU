package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thinking probe (G4, THINK-1): does Gemma 4 think through the runtime's own template (I8) when a conversation asks
 * for it, and at what cost? The same made-up question runs with thinking off and on; the test reports the thought
 * channel's size, the answer, and the time to the answer's first word. It passes either way: it measures.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class ThinkingProbeTest {

    @Test
    fun thinkingOnAndOff() {
        S1.assumeModelPresent()
        ExperimentalFlags.enableBenchmark = true
        try {
            Engine(EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())).use { engine ->
                engine.initialize()
                for (thinking in listOf(false, true)) run(engine, thinking)
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
        }
    }

    private fun run(engine: Engine, thinking: Boolean) {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = 512),
        )
        engine.createConversation(config).use { conversation ->
            val answer = StringBuilder()
            val thought = StringBuilder()
            val channels = mutableSetOf<String>()
            var firstAnswer = 0L
            val done = CountDownLatch(1)
            val sent = SystemClock.elapsedRealtime()
            conversation.sendMessageAsync(
                Message.user(Contents.of(QUESTION)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        message.channels.forEach { (name, text) -> channels += name; thought.append(text) }
                        val text = message.text()
                        if (text.isNotEmpty() && firstAnswer == 0L) firstAnswer = SystemClock.elapsedRealtime()
                        answer.append(text)
                    }

                    override fun onDone() = done.countDown()

                    override fun onError(throwable: Throwable) {
                        S1.report("thinking_${thinking}_error", throwable.message ?: throwable::class.simpleName.orEmpty())
                        done.countDown()
                    }
                },
            )
            done.await(10, TimeUnit.MINUTES)
            S1.report("thinking_${thinking}_channels", channels.joinToString(",").ifEmpty { "none" })
            S1.report("thinking_${thinking}_thought_chars", thought.length)
            S1.report("thinking_${thinking}_first_answer_ms", if (firstAnswer > 0) firstAnswer - sent else -1)
            S1.report("thinking_${thinking}_answer", answer.toString().take(160).replace('\n', ' '))
            val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            info?.let { S1.report("thinking_${thinking}_decode_tokens", it.lastDecodeTokenCount) }
        }
    }

    private companion object {
        const val QUESTION = "A train leaves at 9:40 and the trip takes 2 hours 35 minutes. Maya needs 20 minutes to walk from the " +
            "station to lunch. What time does she arrive at lunch? Answer with the time."
    }
}
