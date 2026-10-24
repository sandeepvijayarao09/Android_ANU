package io.github.sandeepvijayarao09.anu.brain.embedding

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import io.github.sandeepvijayarao09.anu.core.model.VisualEmbedder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmbeddingGemma 2, text and vision (ADR-111), on LiteRT-LM's `EmbeddingEngine`: words and pictures in one space,
 * so live camera can match "anything to drink" to what each box shows.
 * - The engine loads on first use, with its vision encoder, and stays until [close].
 * - Text gets EmbeddingGemma 2's task prefixes (a search query, or a search result); pictures go in as they are.
 * - Vectors are cut to [EmbeddingCatalog.VISION_DIMENSIONS] values (Matryoshka) and normalized by the runtime.
 * - [tpu] runs the Tensor G5 build on the TPU, through Google's dispatch library in [nativeLibraryDir] (ADR-102).
 */
class Gemma2Embedder(
    private val modelFile: File,
    private val cacheDir: File,
    private val tpu: Boolean = false,
    private val nativeLibraryDir: String? = null,
    /** Runs on the GPU instead of the CPU (not with [tpu]). */
    private val gpu: Boolean = false,
    /** Soft tokens a picture becomes: 70 or 140 (the model card's sizes); null keeps the runtime's default. */
    private val visionTokens: Int? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : VisualEmbedder, AutoCloseable {
    override val model = EmbeddingCatalog.VISION_MODEL_ID

    /** Where it runs, for the camera's line and the speed log. */
    val backend: String get() = if (tpu) "TPU" else if (gpu) "GPU" else "CPU"

    private val lock = Mutex()
    private var loaded: EmbeddingEngine? = null
    private val options = EmbeddingOptions(true, null, EmbeddingCatalog.VISION_DIMENSIONS, visionTokens)

    override suspend fun embed(texts: List<String>, kind: EmbeddingKind): List<FloatArray> =
        run(texts.map { InputData.Text(prefixed(it, kind)) })

    override suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> = run(jpegs.map { InputData.Image(it) })

    private suspend fun run(items: List<InputData>): List<FloatArray> {
        if (items.isEmpty()) return emptyList()
        return withContext(dispatcher) {
            lock.withLock {
                val engine = loaded ?: load().also { loaded = it }
                // One at a time: the runtime's batch call took twice as long for the 80 labels on the emulator (S14).
                items.map { engine.computeEmbedding(listOf(it), options).embedding }
            }
        }
    }

    private fun load(): EmbeddingEngine {
        cacheDir.mkdirs()
        val backend = when {
            tpu -> Backend.NPU(nativeLibraryDir ?: error("The TPU needs the app's library folder"))
            gpu -> Backend.GPU()
            else -> Backend.CPU()
        }
        val config = EmbeddingEngineConfig(modelFile.path, backend, backend, null, cacheDir.path, null, null, visionTokens)
        return EmbeddingEngine(config).also { it.initialize() }
    }

    /** Frees the engine once any embedding in progress is done; the next use loads it again. */
    suspend fun release() = lock.withLock { close() }

    override fun close() {
        loaded?.close()
        loaded = null
    }

    companion object {
        /** EmbeddingGemma 2's instructions, put before a text (the model card's asymmetric prefixes). */
        fun prefixed(text: String, kind: EmbeddingKind): String =
            (if (kind == EmbeddingKind.QUERY) "task: search query | text: " else "task: search result | text: ") + text.trim()

        /**
         * The embedder, if a build is installed at its catalog size: the Tensor G5 build where this is that chip and
         * its file is there, else the CPU build.
         */
        fun installed(
            modelsDir: File,
            cacheDir: File,
            tensorG5: Boolean = false,
            nativeLibraryDir: String? = null,
            /** `Build.SOC_MODEL`: the GPU is used on a chip in [GPU_VERIFIED], never on an emulator. */
            socModel: String = "",
            emulator: Boolean = false,
        ): Gemma2Embedder? {
            val tpuFile = File(modelsDir, EmbeddingCatalog.VISION_TENSOR_G5.name)
            if (tensorG5 && tpuFile.length() == EmbeddingCatalog.VISION_TENSOR_G5.bytes) {
                return Gemma2Embedder(tpuFile, cacheDir, tpu = true, nativeLibraryDir = nativeLibraryDir, visionTokens = VISION_TOKENS)
            }
            val file = File(modelsDir, EmbeddingCatalog.VISION.name)
            if (file.length() != EmbeddingCatalog.VISION.bytes) return null
            return Gemma2Embedder(file, cacheDir, gpu = !emulator && socModel in GPU_VERIFIED, visionTokens = VISION_TOKENS)
        }

        /**
         * Chips whose GPU ran EmbeddingGemma 2 in S14 with the CPU's answers: the Galaxy S26 Ultra's Snapdragon 8 Elite
         * Gen 5, 132 ms a crop against 369 on its CPU. Others use the CPU until they're measured (as ADR-059).
         */
        val GPU_VERIFIED = setOf("SM8850")

        /**
         * A picture becomes 70 soft tokens, the smaller of the model's two sizes: on the S26 Ultra it gave the same matches as
         * the runtime's default, and named as many crops as YOLOX did (20 of 33), 3.6 times faster on the CPU (S14).
         */
        const val VISION_TOKENS = 70
    }
}
