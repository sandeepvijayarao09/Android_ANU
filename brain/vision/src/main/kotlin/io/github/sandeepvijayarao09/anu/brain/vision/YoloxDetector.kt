package io.github.sandeepvijayarao09.anu.brain.vision

import android.content.res.AssetManager
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.CompiledModel.QualcommOptions.HtpPerformanceMode
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import io.github.sandeepvijayarao09.anu.core.model.Detection
import io.github.sandeepvijayarao09.anu.core.model.DetectorLabel
import io.github.sandeepvijayarao09.anu.core.model.Frame
import io.github.sandeepvijayarao09.anu.core.model.ObjectDetector

/**
 * YOLOX-Nano on LiteRT's CompiledModel (ADR-111): Megvii's Apache 2.0 detector, re-authored by litert-community so
 * its whole graph runs on LiteRT's GPU. It ships in this module's assets (2.2 MB, 16-bit weights, 416 × 416).
 * - A chip in [NPU_VERIFIED] runs it on the NPU when the app carries Qualcomm's libraries (ADR-115, the `personal`
 *   build); LiteRT compiles it for the NPU as it loads, in under a second.
 * - Other phones run it on the GPU. A backend that won't load it falls to the next: the NPU to the GPU, the GPU to the
 *   CPU. Emulators use the CPU (as for Gemma, ADR-025).
 * - Frames go in and come out as numbers in memory. Nothing is saved.
 */
class YoloxDetector private constructor(
    private val compiled: CompiledModel,
    override val backend: String,
    private val environment: Environment? = null,
) : ObjectDetector {
    override val model: String = MODEL
    override val labels: List<DetectorLabel> = CocoLabels.ALL

    private val yolox = Yolox()
    private val input = FloatArray(Yolox.SIZE * Yolox.SIZE * 3)
    private val inputs: List<TensorBuffer> = compiled.createInputBuffers()
    private val outputs: List<TensorBuffer> = compiled.createOutputBuffers()

    override fun detect(frame: Frame): List<Detection> {
        yolox.letterbox(frame, input)
        inputs[0].writeFloat(input)
        compiled.run(inputs, outputs)
        return yolox.decode(outputs[0].readFloat(), yolox.scale(frame), frame.width, frame.height)
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        compiled.close()
        environment?.close()
    }

    companion object {
        const val MODEL = "YOLOX-Nano"
        const val ASSET = "yolox_nano.tflite"

        /** Chips, as `Build.SOC_MODEL` names them, where YOLOX passed spike S15 on the NPU: the same boxes, faster than the GPU. */
        val NPU_VERIFIED = setOf("SM8850")

        /** Qualcomm's dispatch library, which the `personal` build carries in its native library folder (ADR-115). */
        const val QUALCOMM_DISPATCH = "libLiteRtDispatch_Qualcomm.so"

        /**
         * Loads the detector on [backend], or the best this device has (see the class). The NPU needs [libraryDir], a
         * folder holding Qualcomm's dispatch library, compiler plugin and QNN libraries and no other dispatch library
         * ([QualcommLibraries] in the app), and runs in [npuMode].
         */
        fun open(
            assets: AssetManager,
            backend: Accelerator? = null,
            libraryDir: String? = null,
            socModel: String = Build.SOC_MODEL,
            npuMode: HtpPerformanceMode = HtpPerformanceMode.BURST,
        ): YoloxDetector {
            val npu = libraryDir != null && java.io.File(libraryDir, QUALCOMM_DISPATCH).exists()
            val first = backend ?: when {
                isEmulator() -> Accelerator.CPU
                npu && socModel in NPU_VERIFIED -> Accelerator.NPU
                else -> Accelerator.GPU
            }
            val order = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU).dropWhile { it != first }
            var failure: Exception? = null
            for (accelerator in order) {
                try {
                    return create(assets, accelerator, libraryDir, npuMode)
                } catch (e: Exception) {
                    failure = failure ?: e
                }
            }
            throw failure ?: IllegalStateException("No backend for $first")
        }

        /** [open] for callers without LiteRT's types: [backend] and [npuMode] by name, null for the defaults. */
        fun openNamed(assets: AssetManager, libraryDir: String?, backend: String? = null, npuMode: String? = null): YoloxDetector =
            open(
                assets, backend?.let { Accelerator.valueOf(it) }, libraryDir,
                npuMode = npuMode?.let { HtpPerformanceMode.valueOf(it) } ?: HtpPerformanceMode.BURST,
            )

        private fun create(assets: AssetManager, accelerator: Accelerator, libraryDir: String?, npuMode: HtpPerformanceMode): YoloxDetector {
            val options = CompiledModel.Options(accelerator)
            if (accelerator == Accelerator.CPU) options.cpuOptions = CompiledModel.CpuOptions(CPU_THREADS, null, null)
            if (accelerator != Accelerator.NPU) return YoloxDetector(CompiledModel.create(assets, ASSET, options), accelerator.name)
            val dir = libraryDir ?: error("The NPU needs the app's library folder")
            options.qualcommOptions = CompiledModel.QualcommOptions(htpPerformanceMode = npuMode)
            val environment = Environment.create(
                mapOf(Environment.Option.DispatchLibraryDir to dir, Environment.Option.CompilerPluginLibraryDir to dir),
            )
            return try {
                YoloxDetector(CompiledModel.create(assets, ASSET, options, environment), accelerator.name, environment)
            } catch (e: Exception) {
                environment.close()
                throw e
            }
        }

        private const val CPU_THREADS = 4

        private fun isEmulator() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.PRODUCT.startsWith("sdk_")
    }
}
