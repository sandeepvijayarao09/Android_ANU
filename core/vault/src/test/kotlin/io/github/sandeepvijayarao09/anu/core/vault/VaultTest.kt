package io.github.sandeepvijayarao09.anu.core.vault

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.security.SecretUse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Vault invariants on the JVM, with an in-memory stand-in for Android Keystore. I1 is checked with a canary. */
class VaultTest {

    @get:Rule val folder = TemporaryFolder()

    private val canary = "CANARY-7f3a-secret-value"
    private val protector = FakeKeyProtector()
    private val uses = mutableListOf<String>()
    private lateinit var vault: Vault
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer

    @Before
    fun setUp() {
        vault = Vault(folder.root, protector, audit = { ref, caller, host, _ -> uses += "$ref $caller $host" })
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
        other.close()
    }

    private suspend fun putKey(hosts: Set<String> = setOf(server.hostName), users: Set<String> = setOf("brain:gemini")) =
        vault.put(
            NewVaultItem(
                type = ItemType.API_KEY,
                label = "Gemini",
                service = "Google AI",
                secret = canary,
                boundHosts = hosts,
                allowedUsers = users,
                protection = Protection.DEVICE_UNLOCKED,
            ),
        )

    private fun client() = OkHttpClient.Builder().vaultClient(vault, requireHttps = false).build()

    private fun call(url: okhttp3.HttpUrl, ref: SecretRef, caller: String = "brain:gemini") =
        client().newCall(
            Request.Builder().url(url).tag(SecretUse::class.java, SecretUse(ref, header = "x-goog-api-key", caller = caller)).build(),
        ).execute()

    @Test
    fun `attaches the secret only as a header on the bound host`() = runTest {
        val info = putKey()
        server.enqueue(MockResponse.Builder().body("ok").build())
        call(server.url("/v1/models?x=1"), info.ref).use { assertEquals(200, it.code) }

        val recorded = server.takeRequest()
        assertEquals(canary, recorded.headers["x-goog-api-key"])
        assertFalse(recorded.url.toString().contains(canary)) // never in the URL (§18)
        assertEquals(listOf("${info.ref} brain:gemini ${server.hostName}"), uses)
    }

    @Test
    fun `refuses unbound hosts and other callers without sending anything`() = runTest {
        val info = putKey(hosts = setOf("generativelanguage.googleapis.com"))
        assertThrows(VaultPolicyException::class.java) { call(server.url("/"), info.ref) }
        val bound = putKey()
        assertThrows(VaultPolicyException::class.java) { call(server.url("/"), bound.ref, caller = "connector:gmail") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect never carries the secret to another host`() = runTest {
        val info = putKey()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/steal")).build())
        call(server.url("/"), info.ref).use { assertEquals(302, it.code) }
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `secrets are only sent over HTTPS by default`() = runTest {
        val info = putKey()
        val strict = OkHttpClient.Builder().withVault(vault).build()
        val request = Request.Builder().url(server.url("/"))
            .tag(SecretUse::class.java, SecretUse(info.ref, header = "x-goog-api-key", caller = "brain:gemini")).build()
        assertThrows(VaultPolicyException::class.java) { strict.newCall(request).execute() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `editing stored metadata to redirect a secret breaks decryption`() = runTest {
        val info = putKey(hosts = setOf("api.example.com"))
        val file = folder.root.resolve("items/${info.id}.json")
        file.writeText(file.readText().replace("api.example.com", server.hostName))
        assertThrows(VaultIntegrityException::class.java) { call(server.url("/"), info.ref) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a locked key surfaces as VaultLockedException`() = runTest {
        val info = putKey()
        protector.locked = true
        assertThrows(VaultLockedException::class.java) { call(server.url("/"), info.ref) }
    }

    @Test
    fun `the secret never appears in files, listings or messages`() = runTest {
        val info = putKey()
        assertTrue(folder.root.walkTopDown().filter { it.isFile }.none { it.readText(Charsets.ISO_8859_1).contains(canary) })
        assertFalse(vault.list().toString().contains(canary))
        val error = runCatching { call(server.url("/"), info.ref, caller = "intruder") }.exceptionOrNull()
        assertFalse(error.toString().contains(canary))
        assertEquals(canary, vault.revealForUser(info.ref)) // only the vault screen can see it
    }

    @Test
    fun `ids that are not vault ids are rejected`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { call(server.url("/"), SecretRef("../keyset-user_presence.bin")) }
    }

    @Test
    fun `delete removes the item`() = runTest {
        val info = putKey()
        assertTrue(vault.delete(info.ref))
        assertNull(vault.list().firstOrNull { it.id == info.id })
    }

    @Test
    fun `logins require the user to be present`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                vault.put(NewVaultItem(ItemType.PASSWORD_LOGIN, "Meta", "Meta Muse", "pw", protection = Protection.DEVICE_UNLOCKED))
            }
        }
    }

    /** Tink AEAD keys in memory, standing in for Android Keystore on the JVM. */
    private class FakeKeyProtector : KeyProtector {
        init {
            AeadConfig.register()
        }

        var locked = false
        private val keys = Protection.entries.associateWith {
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }

        override fun masterKey(protection: Protection): Aead = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                if (locked) throw VaultLockedException() else keys.getValue(protection).encrypt(plaintext, associatedData)

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                if (locked) throw VaultLockedException() else keys.getValue(protection).decrypt(ciphertext, associatedData)
        }
    }
}
