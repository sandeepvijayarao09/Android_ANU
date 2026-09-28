package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.core.model.InstallOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class CatalogInstallerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val chat = bytes("chat model", 64)
    private val gpu = bytes("gpu build", 48)
    private val search = bytes("search model", 40)
    private val tokenizer = bytes("tokenizer", 24)

    private val tokenizerFile = known("sentencepiece.model", tokenizer, "EmbeddingGemma's tokenizer", pairedWith = "embedding.tflite")
    private val searchFile = known("embedding.tflite", search, "EmbeddingGemma 300M", pairedWith = "sentencepiece.model")
    private val chatFile = known("gemma.litertlm", chat, "Gemma 4 E4B")
    private val gpuFile = known("gemma-gpu.litertlm", gpu, "Gemma 4 E4B's GPU build")

    private fun installer(dir: File = folder.root, installed: MutableList<KnownFile> = mutableListOf(), free: Long = Long.MAX_VALUE) =
        CatalogInstaller(
            dir,
            wanted = { listOf(chatFile, searchFile, tokenizerFile) },
            known = listOf(chatFile, gpuFile, searchFile, tokenizerFile),
            onInstalled = { installed += it },
            freeSpace = { free },
        )

    @Test
    fun `an exact catalog file is installed under its catalog name, whatever it was called`() = runBlocking {
        val installed = mutableListOf<KnownFile>()
        val progress = mutableListOf<Long>()
        val outcome = installer(installed = installed).install(chat.size.toLong(), { chat.inputStream() }) { done, _ -> progress += done }
        assertEquals(InstallOutcome.Done("Gemma 4 E4B"), outcome)
        assertTrue(File(folder.root, "gemma.litertlm").readBytes().contentEquals(chat))
        assertEquals(listOf(chatFile), installed)
        assertEquals(chat.size.toLong(), progress.last())
        assertFalse("no partial file is left", File(folder.root, "install.part").exists())
    }

    @Test
    fun `a file of the right size with other bytes is refused and deleted`() = runBlocking {
        val tampered = chat.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val outcome = installer().install(tampered.size.toLong(), { tampered.inputStream() })
        assertEquals(InstallOutcome.Refused("That file isn't Gemma 4 E4B byte for byte, so it wasn't installed. It may be incomplete or changed."), outcome)
        assertFalse(File(folder.root, "gemma.litertlm").exists())
        assertFalse(File(folder.root, "install.part").exists())
    }

    @Test
    fun `a file whose size matches nothing is refused before it is read`() = runBlocking {
        var opened = false
        val outcome = installer().install(12345, { opened = true; ByteArray(12345).inputStream() })
        assertTrue(outcome is InstallOutcome.Refused)
        assertFalse("never read", opened)
    }

    @Test
    fun `a catalog file this phone doesn't use is named`() = runBlocking {
        val outcome = installer().install(gpu.size.toLong(), { gpu.inputStream() })
        assertEquals(InstallOutcome.Refused("That's Gemma 4 E4B's GPU build, which this phone doesn't use."), outcome)
    }

    @Test
    fun `with no size given, a file is identified by its bytes, and one larger than any model stops early`() = runBlocking {
        assertEquals(InstallOutcome.Done("EmbeddingGemma's tokenizer", "EmbeddingGemma 300M (embedding.tflite)"), installer().install(null, { tokenizer.inputStream() }))
        var read = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 1.also { read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(1, off, off + len); read += len; return len }
        }
        assertEquals(InstallOutcome.Refused("That isn't a file Anu knows. Anu installs only the model files it lists, checked byte for byte."), installer().install(null, { endless }))
        assertTrue("stopped soon after the largest model's size, not at the end", read < chat.size + (1 shl 16) + 1)
    }

    @Test
    fun `each of search by meaning's two files says it needs the other until both are installed`() = runBlocking {
        assertEquals(InstallOutcome.Done("EmbeddingGemma's tokenizer", "EmbeddingGemma 300M (embedding.tflite)"), installer().install(tokenizer.size.toLong(), { tokenizer.inputStream() }))
        assertEquals(InstallOutcome.Done("EmbeddingGemma 300M"), installer().install(search.size.toLong(), { search.inputStream() }))
    }

    @Test
    fun `an installed file isn't copied again`() = runBlocking {
        File(folder.root, "gemma.litertlm").writeBytes(chat)
        var opened = false
        assertEquals(InstallOutcome.AlreadyThere("Gemma 4 E4B"), installer().install(chat.size.toLong(), { opened = true; chat.inputStream() }))
        assertFalse(opened)
    }

    @Test
    fun `without enough free space nothing is copied`() = runBlocking {
        val outcome = installer(free = 10).install(chat.size.toLong(), { chat.inputStream() })
        assertTrue(outcome is InstallOutcome.Refused && "free space" in outcome.reason)
        assertFalse(File(folder.root, "install.part").exists())
    }

    private fun bytes(seed: String, size: Int) = ByteArray(size) { i -> (seed.hashCode() + i * 31).toByte() }

    private fun known(name: String, content: ByteArray, what: String, pairedWith: String? = null) =
        KnownFile(name, content.size.toLong(), sha256(content), what, pairedWith)

    private fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
}
