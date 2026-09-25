package io.github.sandeepvijayarao09.anu.core.vault

import com.google.crypto.tink.Aead

/** Supplies the master key that wraps the vault's Tink keyset for each [Protection] level. */
fun interface KeyProtector {
    fun masterKey(protection: Protection): Aead
}

/**
 * The vault's Android Keystore master keys (§11), one per protection level:
 * - USER_PRESENCE: usable for [sessionSeconds] after biometric or device credential.
 * - DEVICE_UNLOCKED: usable only while the phone is unlocked.
 */
class KeystoreKeyProtector(private val sessionSeconds: Int = 300) : KeyProtector {
    override fun masterKey(protection: Protection): Aead = when (protection) {
        Protection.USER_PRESENCE -> KeystoreAead("anu.vault.master.user_presence", KeyPolicy.UserPresence(sessionSeconds))
        Protection.DEVICE_UNLOCKED -> KeystoreAead("anu.vault.master.device_unlocked", KeyPolicy.UnlockedDevice)
    }
}
