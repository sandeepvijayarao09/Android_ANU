package io.github.sandeepvijayarao09.anu.brain.vision

import android.content.res.AssetManager
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import io.github.sandeepvijayarao09.anu.core.model.Detection
import io.github.sandeepvijayarao09.anu.core.model.DetectorLabel
import io.github.sandeepvijayarao09.anu.core.model.Frame
import io.github.sandeepvijayarao09.anu.core.model.ObjectDetector

/**
 * YOLOX-Nano on LiteRT's CompiledModel (ADR-111): Megvii's Apache 2.0 detector, re-authored by litert-community so
 * its whole graph runs on LiteRT's GPU. It ships in this module's assets (2.2 MB, 16-bit weights, 416 × 416).
 * - Phones run it on the GPU, and on the CPU if the GPU won't load it. Emulators use the CPU (as for Gemma, ADR-025).
 * - The NPU is for spike S15's device tests, which bring Qualcomm's libraries; it runs in burst mode.
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

        /**
         * Loads the detector on [backend], or the best this device has: the GPU on a phone, the CPU on an emulator.
         * The NPU (spike S15) needs [libraryDir], the folder holding the vendor's dispatch library and compiler plugin;
         * LiteRT compiles the model for the NPU as it loads.
         */
        fun open(assets: AssetManager, backend: Accelerator? = null, libraryDir: String? = null): YoloxDetector {
            val first = backend ?: if (isEmulator()) Accelerator.CPU else Accelerator.GPU
            return try {
                create(assets, first, libraryDir)
            } catch (e: Exception) {
                if (first == Accelerator.CPU) throw e
                create(assets, Accelerator.CPU, null)
            }
        }

        private fun create(assets: AssetManager, accelerator: Accelerator, libraryDir: String?): YoloxDetector {
            val options = CompiledModel.Options(accelerator)
            if (accelerator == Accelerator.CPU) options.cpuOptions = CompiledModel.CpuOptions(CPU_THREADS, null, null)
            if (accelerator != Accelerator.NPU) return YoloxDetector(CompiledModel.create(assets, ASSET, options), accelerator.name)
            val dir = libraryDir ?: error("The NPU needs the app's library folder")
            options.qualcommOptions = CompiledModel.QualcommOptions(htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST)
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
