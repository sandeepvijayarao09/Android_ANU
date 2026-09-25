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

/** When an Android Keystore key may be used (ARCHITECTURE §11, §14). */
sealed interface KeyPolicy {
    /** For [sessionSeconds] after biometric or device-credential authentication. Vault logins. */
    data class UserPresence(val sessionSeconds: Int) : KeyPolicy

    /** Only while the phone is unlocked. Vault API keys. */
    data object UnlockedDevice : KeyPolicy

    /**
     * Whenever the app can run, with no user check. App data lives in credential-encrypted storage,
     * so in practice this means after the first unlock since boot. Used for the database key, so
     * background work can run while the phone is locked (ADR-024).
     */
    data object AfterFirstUnlock : KeyPolicy
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key, created on first use. StrongBox is used when
 * available. Errors are mapped to the vault's exceptions:
 * - a key that needs authentication becomes [VaultLockedException];
 * - an invalidated key becomes [VaultIntegrityException].
 */
class KeystoreAead(private val alias: String, private val policy: KeyPolicy) : Aead {

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
        throw VaultIntegrityException("The device key $alias was invalidated", e)
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
                when (policy) {
                    is KeyPolicy.UserPresence -> {
                        setUserAuthenticationRequired(true)
                        setUserAuthenticationParameters(
                            policy.sessionSeconds,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        )
                        // A new fingerprint must not wipe the vault while there's no recovery passphrase (§11).
                        setInvalidatedByBiometricEnrollment(false)
                    }
                    KeyPolicy.UnlockedDevice -> setUnlockedDeviceRequired(true)
                    KeyPolicy.AfterFirstUnlock -> Unit
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
