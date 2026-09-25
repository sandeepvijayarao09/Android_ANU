package io.github.sandeepvijayarao09.anu.core.vault

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID

/**
 * Stores the secrets Anu uses for the user, such as API keys and app passwords (ARCHITECTURE §11).
 *
 * - **Keys.** Each [Protection] level has one Tink AES-256-GCM keyset, wrapped by a Keystore master key
 *   ([KeyProtector]).
 * - **Binding.** Each item is encrypted with associated data that binds its id, protection, hosts and
 *   allowed users. Editing the stored metadata to redirect a secret makes decryption fail.
 * - **Access.** Nothing here returns a secret to other code. [useSecret] is internal and only
 *   [SecretInjector] calls it, to attach a secret to one HTTP request for a bound host (I1).
 *   [revealForUser] exists for the vault screen alone.
 */
class Vault(
    private val directory: File,
    private val protector: KeyProtector,
    private val audit: VaultAudit = VaultAudit.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        AeadConfig.register()
    }

    private val lock = Any()
    private val json = Json { encodeDefaults = true }
    private val itemsDir: File get() = File(directory, "items").apply { mkdirs() }

    suspend fun put(item: NewVaultItem): VaultItemInfo = withContext(io) {
        require(item.protection == Protection.USER_PRESENCE || item.type == ItemType.API_KEY || item.type == ItemType.OAUTH_TOKEN) {
            "Only API keys and tokens may be usable without the user present"
        }
        val info = VaultItemInfo(
            id = UUID.randomUUID().toString(),
            type = item.type,
            label = item.label.trim(),
            service = item.service.trim(),
            username = item.username,
            boundHosts = item.boundHosts.mapTo(sortedSetOf(), ::normalizeHost),
            allowedUsers = item.allowedUsers.toSortedSet(),
            protection = item.protection,
            createdAtMillis = clock(),
        )
        val ciphertext = synchronized(lock) { itemAead(info.protection).encrypt(item.secret.toByteArray(), binding(info)) }
        synchronized(lock) { write(StoredItem(info, Base64.getEncoder().encodeToString(ciphertext))) }
        info
    }

    suspend fun list(): List<VaultItemInfo> = withContext(io) {
        itemsDir.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty()
            .mapNotNull { runCatching { read(it).info }.getOrNull() }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun delete(ref: SecretRef): Boolean = withContext(io) { synchronized(lock) { fileFor(ref).delete() } }

    /** For the vault screen only, after it has shown a biometric prompt (§11). Tools and brains must never call this. */
    suspend fun revealForUser(ref: SecretRef): String = withContext(io) { decrypt(read(fileFor(ref))) }

    /** Blocking. Runs on OkHttp's thread when called from [SecretInjector]. */
    internal fun <T> useSecret(ref: SecretRef, host: String, caller: String, block: (String) -> T): T {
        val stored = read(fileFor(ref))
        val info = stored.info
        if (host.lowercase() !in info.boundHosts) throw VaultPolicyException("${info.label} is not bound to $host")
        if (caller !in info.allowedUsers) throw VaultPolicyException("$caller may not use ${info.label}")
        val value = decrypt(stored)
        val now = clock()
        synchronized(lock) { write(stored.copy(info = info.copy(lastUsedAtMillis = now))) }
        audit.used(ref, caller, host.lowercase(), now)
        return block(value)
    }

    private fun decrypt(stored: StoredItem): String = try {
        val aead = synchronized(lock) { itemAead(stored.info.protection) }
        String(aead.decrypt(Base64.getDecoder().decode(stored.ciphertext), binding(stored.info)))
    } catch (e: GeneralSecurityException) {
        throw VaultIntegrityException("${stored.info.label} failed its integrity check", e)
    }

    /** Loads the keyset for [protection], creating it on first use. Callers hold [lock]. */
    private fun itemAead(protection: Protection): Aead {
        val file = File(directory, "keyset-${protection.name.lowercase()}.bin")
        val master = RecordingAead(protector.masterKey(protection))
        val associatedData = "anu-vault-keyset-v1|${protection.name}".toByteArray()
        val config = RegistryConfiguration.get()
        val handle = try {
            if (file.exists()) {
                TinkProtoKeysetFormat.parseEncryptedKeyset(file.readBytes(), master, associatedData, config)
            } else {
                KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).also {
                    atomicWrite(file, TinkProtoKeysetFormat.serializeEncryptedKeyset(it, master, associatedData, config))
                }
            }
        } catch (e: GeneralSecurityException) {
            // Tink replaces the master key's error with its own ("Parse keyset failed"), so rethrow the original.
            master.failure?.let { if (it is VaultLockedException || it is VaultIntegrityException) throw it }
            throw e
        }
        return handle.getPrimitive(config, Aead::class.java)
    }

    /** Remembers the master key's last error, which Tink would otherwise hide. */
    private class RecordingAead(private val inner: Aead) : Aead {
        var failure: Throwable? = null
            private set

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            recording { inner.encrypt(plaintext, associatedData) }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
            recording { inner.decrypt(ciphertext, associatedData) }

        private inline fun recording(block: () -> ByteArray): ByteArray = try {
            block()
        } catch (t: Throwable) {
            failure = t
            throw t
        }
    }

    private fun binding(info: VaultItemInfo): ByteArray = listOf(
        "anu-vault-item-v1",
        info.id,
        info.type.name,
        info.protection.name,
        info.boundHosts.sorted().joinToString(","),
        info.allowedUsers.sorted().joinToString(","),
    ).joinToString("|").toByteArray()

    private fun fileFor(ref: SecretRef): File {
        require(ID.matches(ref.id)) { "Not a vault id" } // blocks path traversal through crafted ids
        return File(itemsDir, ref.id + SUFFIX)
    }

    private fun read(file: File): StoredItem {
        if (!file.exists()) throw VaultPolicyException("No such vault item")
        return json.decodeFromString(StoredItem.serializer(), file.readText())
    }

    private fun write(item: StoredItem) =
        atomicWrite(File(itemsDir, item.info.id + SUFFIX), json.encodeToString(StoredItem.serializer(), item).toByteArray())

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) throw VaultIntegrityException("Could not write ${target.name}")
    }

    @Serializable
    private data class StoredItem(val info: VaultItemInfo, val ciphertext: String)

    private companion object {
        const val SUFFIX = ".json"
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val HOST = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")

        fun normalizeHost(host: String): String = host.trim().lowercase().also {
            require(HOST.matches(it)) { "Bound hosts are bare host names such as api.example.com, not URLs: $host" }
        }
    }
}
