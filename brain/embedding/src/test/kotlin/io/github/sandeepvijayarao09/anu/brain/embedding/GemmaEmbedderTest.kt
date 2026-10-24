package io.github.sandeepvijayarao09.anu.brain.embedding

import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class GemmaEmbedderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: Long): File = File(folder.root, name).also { RandomAccessFile(it, "rw").use { f -> f.setLength(bytes) } }

    @Test
    fun `it is installed only with both files at their catalog sizes, on an arm64 phone`() {
        val arm = listOf("arm64-v8a")
        assertNull(GemmaEmbedder.installed(folder.root, arm))
        file(EmbeddingCatalog.MODEL.name, EmbeddingCatalog.MODEL.bytes)
        assertNull("the tokenizer is missing", GemmaEmbedder.installed(folder.root, arm))
        file(EmbeddingCatalog.TOKENIZER.name, EmbeddingCatalog.TOKENIZER.bytes - 1)
        assertNull("a partial copy doesn't count", GemmaEmbedder.installed(folder.root, arm))
        file(EmbeddingCatalog.TOKENIZER.name, EmbeddingCatalog.TOKENIZER.bytes)
        assertNotNull(GemmaEmbedder.installed(folder.root, arm))
        assertNull("its native code is arm64 only", GemmaEmbedder.installed(folder.root, listOf("x86_64")))
    }
}

class Gemma2EmbedderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: Long): File = File(folder.root, name).also { RandomAccessFile(it, "rw").use { f -> f.setLength(bytes) } }

    @Test
    fun `it is installed at its catalog size, and the TPU build is used only on a Tensor G5`() {
        val cache = File(folder.root, "cache")
        assertNull(Gemma2Embedder.installed(folder.root, cache))
        file(EmbeddingCatalog.VISION.name, EmbeddingCatalog.VISION.bytes - 1)
        assertNull("a partial copy doesn't count", Gemma2Embedder.installed(folder.root, cache))
        file(EmbeddingCatalog.VISION.name, EmbeddingCatalog.VISION.bytes)
        assertEquals("CPU", Gemma2Embedder.installed(folder.root, cache)?.backend)
        file(EmbeddingCatalog.VISION_TENSOR_G5.name, EmbeddingCatalog.VISION_TENSOR_G5.bytes)
        assertEquals("CPU", Gemma2Embedder.installed(folder.root, cache, tensorG5 = false)?.backend)
        assertEquals("TPU", Gemma2Embedder.installed(folder.root, cache, tensorG5 = true, nativeLibraryDir = "/lib")?.backend)
        // The GPU only on a chip S14 measured, and never on an emulator.
        File(folder.root, EmbeddingCatalog.VISION_TENSOR_G5.name).delete()
        assertEquals("GPU", Gemma2Embedder.installed(folder.root, cache, socModel = "SM8850")?.backend)
        assertEquals("CPU", Gemma2Embedder.installed(folder.root, cache, socModel = "SM8850", emulator = true)?.backend)
        assertEquals("CPU", Gemma2Embedder.installed(folder.root, cache, socModel = "Tensor G5")?.backend)
    }

    @Test
    fun `queries and documents get EmbeddingGemma 2's task prefixes`() {
        assertEquals("task: search query | text: anything to drink", Gemma2Embedder.prefixed("  anything to drink ", EmbeddingKind.QUERY))
        assertEquals("task: search result | text: cup: a cup or mug", Gemma2Embedder.prefixed("cup: a cup or mug", EmbeddingKind.DOCUMENT))
    }
}
