package io.github.sandeepvijayarao09.anu.core.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import com.google.crypto.tink.Aead
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supplies the master key that wraps the vault's Tink keyset for each [Protection] level. */
fun interface KeyProtector {
    fun masterKey(protection: Protection): Aead
}

/**
 * Android Keystore master keys (§11). They are hardware-backed, and StrongBox is used when the device
 * has it. They are never exportable.
 * - USER_PRESENCE: authentication-bound, usable for [sessionSeconds] after biometric or device credential.
 * - DEVICE_UNLOCKED: usable only while the device is unlocked.
 *
 * New biometric enrollment does not invalidate the keys. Without a recovery passphrase (Planned),
 * invalidation would silently destroy the vault, and the device credential already guards access.
 */
class KeystoreKeyProtector(private val sessionSeconds: Int = 300) : KeyProtector {

    override fun masterKey(protection: Protection): Aead = KeystoreAead(protection)

    private inner class KeystoreAead(private val protection: Protection) : Aead {
        private val alias = "anu.vault.master.${protection.name.lowercase()}"

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = guarded {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(associatedData)
            cipher.iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = guarded {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES))
            cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext, IV_BYTES, ciphertext.size - IV_BYTES)
        }

        private fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: UserNotAuthenticatedException) {
            throw VaultLockedException(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultIntegrityException("The vault's device key was invalidated", e)
        }

        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
            return try {
                generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        }

        private fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .apply {
                    when (protection) {
                        Protection.USER_PRESENCE -> {
                            setUserAuthenticationRequired(true)
                            setUserAuthenticationParameters(
                                sessionSeconds,
                                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                            )
                            setInvalidatedByBiometricEnrollment(false)
                        }
                        Protection.DEVICE_UNLOCKED -> setUnlockedDeviceRequired(true)
                    }
                }
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
