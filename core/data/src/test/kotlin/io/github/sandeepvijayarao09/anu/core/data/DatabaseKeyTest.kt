package io.github.sandeepvijayarao09.anu.core.data

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException

class DatabaseKeyTest {

    @get:Rule val folder = TemporaryFolder()

    private fun aead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val wrapper = aead()

    @Test
    fun `the key is created once and then reused`() {
        val file = folder.root.resolve("db/key.bin")
        val first = String(DatabaseKey(file, wrapper).sqlcipherKey())
        val second = String(DatabaseKey(file, wrapper).sqlcipherKey())
        assertEquals(first, second)
        assertTrue(first, Regex("x'[0-9a-f]{64}'").matches(first))
    }

    @Test
    fun `the stored file holds only the wrapped key`() {
        val file = folder.root.resolve("db/key.bin")
        val hex = String(DatabaseKey(file, wrapper).sqlcipherKey()).removePrefix("x'").removeSuffix("'")
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertFalse(file.readBytes().toList().windowed(raw.size).any { it == raw.toList() })
    }

    @Test
    fun `a different device key cannot unwrap it`() {
        val file = folder.root.resolve("db/key.bin")
        DatabaseKey(file, wrapper).sqlcipherKey()
        assertThrows(GeneralSecurityException::class.java) { DatabaseKey(file, aead()).sqlcipherKey() }
    }
}
