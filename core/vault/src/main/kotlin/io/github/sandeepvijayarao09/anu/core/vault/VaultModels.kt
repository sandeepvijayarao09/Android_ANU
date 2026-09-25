package io.github.sandeepvijayarao09.anu.core.vault

import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
enum class ItemType { API_KEY, PASSWORD_LOGIN, OAUTH_TOKEN, APP_PASSWORD, SECURE_NOTE }

/** How the item's key is guarded by Android Keystore (§11). */
@Serializable
enum class Protection {
    /** Usable only within a short session after biometric or device-credential authentication. Default for logins. */
    USER_PRESENCE,

    /** Usable whenever the device is unlocked, so background features can call an API. For API keys only. */
    DEVICE_UNLOCKED,
}

/** A new item as entered by the user. [secret] is encrypted immediately and never stored in plain text. */
class NewVaultItem(
    val type: ItemType,
    val label: String,
    val service: String,
    val secret: String,
    val username: String? = null,
    /** The only hosts this secret may be sent to. Empty means it is stored for the user only and never sent. */
    val boundHosts: Set<String> = emptySet(),
    /** Components allowed to use it, for example "brain:gemini". */
    val allowedUsers: Set<String> = emptySet(),
    val protection: Protection = Protection.USER_PRESENCE,
) {
    override fun toString() = "NewVaultItem($type, $label, $service)" // never print the secret
}

/** Everything about an item except its secret. Safe to show in lists and logs. */
@Serializable
data class VaultItemInfo(
    val id: String,
    val type: ItemType,
    val label: String,
    val service: String,
    val username: String? = null,
    val boundHosts: Set<String>,
    val allowedUsers: Set<String>,
    val protection: Protection,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long? = null,
) {
    val ref: SecretRef get() = SecretRef(id)
}

/** The Keystore key needs a fresh biometric or device-credential check. Prompt the user, then retry. */
class VaultLockedException(cause: Throwable? = null) : IOException("Vault is locked; authenticate and retry", cause)

/** The request is not allowed to use this secret: wrong host, wrong caller, or not HTTPS. */
class VaultPolicyException(message: String) : IOException(message)

/** The stored item was modified or its key is gone. The item can't be used. */
class VaultIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Records each use of a secret, never its value (§11). */
fun interface VaultAudit {
    fun used(ref: SecretRef, caller: String, host: String, atMillis: Long)

    companion object {
        val None = VaultAudit { _, _, _, _ -> }
    }
}
