package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import org.junit.Assume.assumeTrue
import java.io.File

// Shared setup for spike S1 (ARCHITECTURE §16). These are real-model tests, and they skip themselves
// when no model is present. Push one first:
//   adb push gemma-4-E4B-it.litertlm /data/local/tmp/anu/models/
// Optional instrumentation arguments: modelPath, backend (CPU | GPU | GOOGLE_TENSOR).

internal object S1 {
    private val args: Bundle get() = InstrumentationRegistry.getArguments()

    val modelPath: String
        get() = args.getString("modelPath") ?: "/data/local/tmp/anu/models/gemma-4-E4B-it.litertlm"

    val backendName: String get() = args.getString("backend") ?: "CPU"

    fun backend(): Backend = when (backendName) {
        "GPU" -> Backend.GPU()
        "GOOGLE_TENSOR" -> Backend.GOOGLE_TENSOR()
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
        val line = "model=${File(modelPath).name} backend=$backendName $name=$value"
        Log.i("AnuS1", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("AnuS1", line) })
    }
}
