package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import org.junit.Assume.assumeTrue
import java.io.File

// Shared setup for spike S1 (ARCHITECTURE §16). These are real-model tests, and they skip themselves
// when no model is present. Push one first:
//   adb push gemma-4-E4B-it.litertlm /data/local/tmp/anu/models/
// Optional instrumentation arguments: modelPath, backend (CPU | GPU | GOOGLE_TENSOR | NPU), speculative (on | off)
// for the brain tests' engines, which otherwise keep the runtime's default, and activation (F32 | F16) for the
// benchmark's engine, which otherwise keeps the backend's default (F32 on the CPU).
// maxNumTokens sets the contract tests' context (4096 by default).

internal object S1 {
    private val args: Bundle get() = InstrumentationRegistry.getArguments()

    val modelPath: String
        get() = args.getString("modelPath") ?: "/data/local/tmp/anu/models/gemma-4-E4B-it.litertlm"

    val backendName: String get() = args.getString("backend") ?: "CPU"

    /** The engine's context, 4,096 as in the app (ADR-040) unless `maxNumTokens` says; an NPU file fixes it when compiled (S15). */
    val maxNumTokens: Int get() = args.getString("maxNumTokens")?.toInt() ?: 4096

    /** Speculative decoding for the brain tests' engines; null leaves the runtime's default. */
    val speculative: Boolean?
        get() = when (args.getString("speculative")) {
            "on" -> true
            "off" -> false
            else -> null
        }

    /** The benchmark engine's activation type; null keeps the backend's default. */
    val activation: ActivationDataType?
        get() = when (args.getString("activation")) {
            "F16" -> ActivationDataType.FLOAT16
            "F32" -> ActivationDataType.FLOAT32
            else -> null
        }

    fun options() = EngineOptions(speculativeDecoding = speculative)

    fun backend(): Backend = when (backendName) {
        "GPU" -> Backend.GPU()
        "GOOGLE_TENSOR" -> Backend.GOOGLE_TENSOR()
        // How Google's AI Edge Gallery runs the Pixel's TPU: the NPU backend, with the app's native library folder.
        "NPU" -> Backend.NPU(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
    }

    /** App-private weight cache (ADR-020). Without it the XNNPack cache is silently skipped. */
    fun cacheDir(): String =
        File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "litertlm-cache")
            .apply { mkdirs() }.absolutePath

    fun assumeModelPresent() {
        assumeTrue("No model at $modelPath; skipping real-model test", File(modelPath).canRead())
    }

    /** Writes a metric to logcat (tag AnuS1) and to the instrumentation status stream. */
    fun report(name: String, value: Any) {
        val line = "model=${File(modelPath).name} backend=$backendName speculative=${speculative ?: "default"} activation=${activation ?: "default"} $name=$value"
        Log.i("AnuS1", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("AnuS1", line) })
    }
}
