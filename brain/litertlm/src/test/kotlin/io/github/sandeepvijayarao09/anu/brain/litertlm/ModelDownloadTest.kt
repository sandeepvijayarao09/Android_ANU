package io.github.sandeepvijayarao09.anu.brain.litertlm

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelDownloadTest {

    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val content = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private lateinit var artifact: ModelArtifact

    @Before
    fun setUp() {
        server.start()
        artifact = ModelArtifact(BrainId.BASE, ModelVariant.CPU, "m.litertlm", server.url("/m.litertlm").toString(), content.size.toLong(), sha)
    }

    @After
    fun tearDown() = server.close()

    private val download = ResumableDownload(OkHttpClient())
    private val target get() = folder.root.resolve("models/m.litertlm")

    @Test
    fun `downloads and verifies a file`() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(content)).build())
        download.run(artifact, target)
        assertTrue(target.readBytes().contentEquals(content))
        assertFalse(folder.root.resolve("models/m.litertlm.part").exists())
    }

    @Test
    fun `resumes a partial file with a range request`() = runTest {
        target.parentFile!!.mkdirs()
        folder.root.resolve("models/m.litertlm.part").writeBytes(content.copyOfRange(0, 50_000))
        server.enqueue(
            MockResponse.Builder().code(206).body(okio.Buffer().write(content.copyOfRange(50_000, content.size))).build(),
        )
        download.run(artifact, target)
        assertEquals("bytes=50000-", server.takeRequest().headers["Range"])
        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun `starts over when the server ignores the range`() = runTest {
        target.parentFile!!.mkdirs()
        folder.root.resolve("models/m.litertlm.part").writeBytes(ByteArray(10_000) { 7 })
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(content)).build())
        download.run(artifact, target)
        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun `a corrupted file is deleted and never moved into place`() {
        val corrupted = content.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(corrupted)).build())
        val error = assertThrows(ModelDownloadException::class.java) { kotlinx.coroutines.runBlocking { download.run(artifact, target) } }
        assertFalse(error.retryable)
        assertFalse(target.exists())
        assertFalse(folder.root.resolve("models/m.litertlm.part").exists())
    }

    @Test
    fun `an already verified file is not downloaded again`() = runTest {
        target.parentFile!!.mkdirs()
        target.writeBytes(content)
        download.run(artifact, target)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `catalog picks CPU by default, GPU on request, and NPU only when allowed and the chip matches`() {
        assertEquals(ModelVariant.CPU, ModelCatalog.choose(BrainId.PRO, "Tensor G3").variant)
        assertEquals(ModelVariant.GPU, ModelCatalog.choose(BrainId.BASE, "Tensor G3", preferred = ModelVariant.GPU).variant)
        assertEquals(ModelVariant.CPU, ModelCatalog.choose(BrainId.BASE, "Tensor G5").variant)
        assertEquals(ModelVariant.NPU_TENSOR_G5, ModelCatalog.choose(BrainId.BASE, "Tensor G5", allowNpu = true).variant)
        // There is no E4B NPU build, so Pro falls back to its preferred build even when NPU is allowed.
        assertEquals(ModelVariant.CPU, ModelCatalog.choose(BrainId.PRO, "Tensor G5", allowNpu = true).variant)
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.choose(BrainId.CLOUD_FLASH, "x") }
    }

    @Test
    fun `a model's space counts its weight cache, and deleting the model deletes the cache (ADR-061)`() {
        val models = folder.newFolder("models")
        val caches = folder.newFolder("litertlm-cache")
        models.resolve("m.litertlm").writeBytes(ByteArray(1_000))
        caches.resolve("m.litertlm_1790527891_1000.xnnpack_cache").writeBytes(ByteArray(600))
        caches.resolve("m-gpu.litertlm_1790527891_900.bin").writeBytes(ByteArray(50))
        assertEquals("another build's cache isn't counted", 1_600, ModelFiles.spaceUsed(models, caches, "m.litertlm"))
        ModelFiles.delete(models, caches, "m.litertlm")
        assertEquals(0, ModelFiles.spaceUsed(models, caches, "m.litertlm"))
        assertTrue(caches.resolve("m-gpu.litertlm_1790527891_900.bin").exists())
        assertEquals("measured in S1", 2_210_334_416, ModelCatalog.choose(BrainId.PRO, "Tensor G5").cacheBytes)
    }

    @Test
    fun `phones use the CPU build until their chip's GPU is verified (ADR-059)`() {
        assertEquals("E4B's GPU build wrote garbage on Tensor G5", ModelVariant.CPU, ModelCatalog.preferredVariant("Tensor G5", emulator = false))
        assertEquals(ModelVariant.GPU, ModelCatalog.preferredVariant("Tensor G9", emulator = false, verified = setOf("Tensor G9")))
        assertEquals("emulators have no OpenCL", ModelVariant.CPU, ModelCatalog.preferredVariant("Tensor G9", emulator = true, verified = setOf("Tensor G9")))
    }

    @Test
    fun `catalog entries are pinned to a commit and carry a full hash`() {
        ModelCatalog.all.forEach {
            assertTrue(it.url, Regex("https://huggingface\\.co/litert-community/.+/resolve/[0-9a-f]{40}/.+").matches(it.url))
            assertTrue(it.sha256, Regex("[0-9a-f]{64}").matches(it.sha256))
        }
    }
}
