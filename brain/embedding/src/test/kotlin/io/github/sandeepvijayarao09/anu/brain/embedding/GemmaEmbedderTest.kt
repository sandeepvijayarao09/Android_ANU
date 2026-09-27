package io.github.sandeepvijayarao09.anu.brain.embedding

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
