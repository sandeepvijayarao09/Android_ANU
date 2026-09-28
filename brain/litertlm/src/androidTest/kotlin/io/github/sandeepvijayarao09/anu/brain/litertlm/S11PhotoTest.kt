package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Spike S11 (§16): a photo in, an answer out, with Gemma 4 on the device. The test draws a made-up picture, a sign
 * reading "LUNCH 12:30" beside a red circle, asks about it, and reports the load time, the tokens the picture
 * takes, the time to the first token, the speed and the peak memory. The vision encoder runs on the GPU, as in
 * Google's AI Edge Gallery, unless `visionBackend=CPU` (the emulator has no GPU for LiteRT).
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class S11PhotoTest {

    @Test
    fun answersAboutAPicture() {
        S1.assumeModelPresent()
        val visionName = InstrumentationRegistry.getArguments().getString("visionBackend") ?: "GPU"
        val vision = if (visionName == "CPU") Backend.CPU() else Backend.GPU()
        S1.report("vision_backend", visionName)
        Capabilities(S1.modelPath).use { S1.report("vision_token_budget", it.maxVisionTokenBudget()) }
        val picture = picture()
        S1.report("picture_bytes", picture.size)
        ExperimentalFlags.enableBenchmark = true
        try {
            val started = SystemClock.elapsedRealtime()
            val config = EngineConfig(
                modelPath = S1.modelPath,
                backend = S1.backend(),
                visionBackend = vision,
                maxNumTokens = 4096,
                maxNumImages = 1,
                cacheDir = S1.cacheDir(),
            )
            Engine(config).use { engine ->
                engine.initialize()
                S1.report("load_ms", SystemClock.elapsedRealtime() - started)
                engine.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use { conversation ->
                    val question = Content.Text("What does the sign in this picture say, and what shape is next to it?")
                    val answer = conversation.sendMessage(Contents.of(Content.ImageBytes(picture), question)).text()
                    val info = conversation.getBenchmarkInfo()
                    S1.report("ttft_ms", (info.timeToFirstTokenInSecond * 1000).toInt())
                    S1.report("prefill_tokens", info.lastPrefillTokenCount)
                    S1.report("prefill_tok_s", "%.1f".format(info.lastPrefillTokensPerSecond))
                    S1.report("decode_tokens", info.lastDecodeTokenCount)
                    S1.report("decode_tok_s", "%.1f".format(info.lastDecodeTokensPerSecond))
                    S1.report("answer", answer.take(200).replace('\n', ' '))
                    assertTrue("reads the sign: $answer", "12:30" in answer || "12.30" in answer)
                    assertTrue("sees the circle: $answer", "circle" in answer.lowercase())
                }
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
        }
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.let { S1.report("peak_rss", it.substringAfter(':').trim()) }
    }

    /** A made-up picture: a sign on the left, a red circle on the right, on white. */
    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("LUNCH 12:30", 30f, 280f, Paint().apply { color = Color.BLACK; textSize = 72f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD })
            drawCircle(630f, 256f, 110f, Paint().apply { color = Color.RED; isAntiAlias = true })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
