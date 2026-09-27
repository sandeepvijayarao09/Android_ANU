package io.github.sandeepvijayarao09.anu.brain.embedding

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import com.google.common.util.concurrent.ListenableFuture
import io.github.sandeepvijayarao09.anu.core.model.Embedder
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.Build
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * EmbeddingGemma on the phone (§8.3, ADR-055), through the AI Edge RAG library's `GemmaEmbeddingModel`.
 * - The model loads on first use and stays while the app runs. It runs on one CPU thread: a message takes
 *   about 1 s on a Pixel 10 Pro and 0.45 s on the emulator (S3). The library's GPU option aborts the process.
 * - Queries and facts use EmbeddingGemma's retrieval tasks, so a question lands near the facts that answer it.
 * - Vectors are cut to [EmbeddingCatalog.DIMENSIONS] values and kept at unit length.
 */
class GemmaEmbedder(
    private val modelFile: File,
    private val tokenizerFile: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Embedder {
    override val model = EmbeddingCatalog.MODEL_ID

    private val lock = Mutex()
    private var loaded: GemmaEmbeddingModel? = null

    override suspend fun embed(texts: List<String>, kind: EmbeddingKind): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return withContext(dispatcher) {
            val engine = lock.withLock { loaded ?: load().also { loaded = it } }
            val task = if (kind == EmbeddingKind.QUERY) EmbedData.TaskType.RETRIEVAL_QUERY else EmbedData.TaskType.RETRIEVAL_DOCUMENT
            val request = EmbeddingRequest.create(texts.map { EmbedData.create(it, task, kind == EmbeddingKind.QUERY) })
            val vectors = if (texts.size == 1) listOf(engine.getEmbeddings(request).await()) else engine.getBatchEmbeddings(request).await()
            vectors.map { values -> cut(FloatArray(values.size) { values[it] }) }
        }
    }

    /** A missing native library is an Error; callers fall back on an Exception, so it becomes one. */
    private fun load(): GemmaEmbeddingModel = try {
        GemmaEmbeddingModel(modelFile.path, tokenizerFile.path, false)
    } catch (e: UnsatisfiedLinkError) {
        throw IllegalStateException("Search by meaning can't run on this phone", e)
    }

    private fun cut(vector: FloatArray): FloatArray {
        val kept = if (vector.size > EmbeddingCatalog.DIMENSIONS) vector.copyOf(EmbeddingCatalog.DIMENSIONS) else vector
        val norm = sqrt(kept.fold(0f) { sum, v -> sum + v * v })
        return if (norm == 0f) kept else FloatArray(kept.size) { kept[it] / norm }
    }

    companion object {
        /**
         * The embedder, if both files are installed with their catalog sizes and the phone can run it: the
         * library's native code is built for arm64 only.
         */
        fun installed(modelsDir: File, abis: List<String> = Build.SUPPORTED_ABIS.toList()): GemmaEmbedder? {
            if ("arm64-v8a" !in abis) return null
            val model = File(modelsDir, EmbeddingCatalog.MODEL.name)
            val tokenizer = File(modelsDir, EmbeddingCatalog.TOKENIZER.name)
            val ready = model.length() == EmbeddingCatalog.MODEL.bytes && tokenizer.length() == EmbeddingCatalog.TOKENIZER.bytes
            return if (ready) GemmaEmbedder(model, tokenizer) else null
        }
    }
}

/** Waits for a Guava future without blocking a thread. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try {
            continuation.resume(get())
        } catch (e: ExecutionException) {
            continuation.resumeWithException(e.cause ?: e)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }, Runnable::run)
    continuation.invokeOnCancellation { cancel(false) }
}
