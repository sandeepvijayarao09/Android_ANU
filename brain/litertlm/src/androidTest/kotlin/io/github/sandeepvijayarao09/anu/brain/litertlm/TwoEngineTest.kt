package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether Base on the TPU and Pro on the GPU can stay loaded together in one process (spike S1, the two-engine proposal):
 * E2B's Tensor G5 file through Backend.NPU, then E4B's generic file on the GPU, each answering a short question, then
 * each again, so both stay usable side by side. It reports the process's resident and peak memory and the memory the
 * system has left after each step. Runner arguments: tpuModelPath and gpuModelPath. Skips itself without both files.
 */
@RunWith(AndroidJUnit4::class)
class TwoEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val tpuPath = args.getString("tpuModelPath") ?: "/data/local/tmp/anu/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm"
    private val gpuPath = args.getString("gpuModelPath") ?: "/data/local/tmp/anu/models/gemma-4-E4B-it.litertlm"

    @Test
    fun keepsTheTpuAndGpuModelsLoadedTogether() {
        assumeTrue("needs both model files", File(tpuPath).canRead() && File(gpuPath).canRead())
        memory("before")
        val tpu = load("tpu", EngineConfig(modelPath = tpuPath, backend = Backend.NPU(context.applicationInfo.nativeLibraryDir), maxNumTokens = 4096, cacheDir = S1.cacheDir()))
        tpu.use {
            val gpu = load("gpu", EngineConfig(modelPath = gpuPath, backend = Backend.GPU(), maxNumTokens = 4096, cacheDir = S1.cacheDir()))
            gpu.use {
                val answers = listOf(
                    ask(tpu, "tpu_1", "What is 12 times 9? Answer with the number only."),
                    ask(gpu, "gpu_1", "What is the capital of France? Answer with one word."),
                    ask(tpu, "tpu_2", "Name one primary colour. One word."),
                    ask(gpu, "gpu_2", "What is 7 plus 8? Answer with the number only."),
                )
                memory("both_used")
                assertTrue("TPU answered 108: ${answers[0]}", "108" in answers[0])
                assertTrue("GPU answered Paris: ${answers[1]}", "paris" in answers[1].lowercase())
                assertTrue("GPU answered 15: ${answers[3]}", "15" in answers[3])
            }
            memory("gpu_closed")
        }
        memory("both_closed")
    }

    private fun load(label: String, config: EngineConfig): Engine {
        val start = SystemClock.elapsedRealtime()
        val engine = Engine(config).also { it.initialize() }
        S1.report("${label}_load_ms", SystemClock.elapsedRealtime() - start)
        memory("${label}_loaded")
        return engine
    }

    private fun ask(engine: Engine, label: String, prompt: String): String {
        val start = SystemClock.elapsedRealtime()
        val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
        val answer = engine.createConversation(config).use { it.sendMessage(prompt).text() }
        S1.report("${label}_answer", "${SystemClock.elapsedRealtime() - start} ms: ${answer.take(80).replace('\n', ' ')}")
        return answer
    }

    /** The process's resident and peak memory, and what the system has left, in MB. */
    private fun memory(step: String) {
        val status = File("/proc/self/status").readLines()
        fun kb(key: String) = status.firstOrNull { it.startsWith(key) }?.filter(Char::isDigit)?.toLongOrNull() ?: -1
        val info = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val gpu = File("/proc/self/smaps_rollup").takeIf(File::canRead)?.readLines()?.firstOrNull { it.startsWith("Rss:") }
        S1.report(
            "memory_$step",
            "rss=${kb("VmRSS:") / 1024} MB peak=${kb("VmHWM:") / 1024} MB system_available=${info.availMem / 1_048_576} MB " +
                "low=${info.lowMemory} threshold=${info.threshold / 1_048_576} MB${gpu?.let { " rollup_$it" }.orEmpty()}",
        )
    }
}
