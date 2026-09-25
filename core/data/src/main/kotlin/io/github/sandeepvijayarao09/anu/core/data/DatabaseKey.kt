package io.github.sandeepvijayarao09.anu.core.data

import com.google.crypto.tink.Aead
import java.io.File
import java.security.SecureRandom

/**
 * The database's encryption key (ADR-024): 256 random bits, created once. On disk it exists only
 * wrapped by [wrapper], a Keystore key that never leaves the device. Copying the database and this
 * file to another device, or out of a backup, reveals nothing.
 */
class DatabaseKey(private val file: File, private val wrapper: Aead) {

    /**
     * The key in SQLCipher's raw-key form, `x'<64 hex digits>'`. The key is already random, so
     * SQLCipher's passphrase stretching is skipped, which also makes opening faster.
     */
    fun sqlcipherKey(): ByteArray = "x'${raw().toHex()}'".toByteArray()

    private fun raw(): ByteArray {
        if (file.exists()) return wrapper.decrypt(file.readBytes(), ASSOCIATED_DATA)
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(wrapper.encrypt(key, ASSOCIATED_DATA))
        check(tmp.renameTo(file)) { "Could not store the database key" }
        return key
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_BYTES = 32
        val ASSOCIATED_DATA = "anu-db-key-v1".toByteArray()
    }
}
