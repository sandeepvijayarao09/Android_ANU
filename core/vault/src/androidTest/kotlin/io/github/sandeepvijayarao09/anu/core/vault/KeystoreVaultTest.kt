package io.github.sandeepvijayarao09.anu.core.vault

import android.app.KeyguardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandeepvijayarao09.anu.core.security.SecretUse
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The vault with the real Android Keystore (§11): secrets are stored, bound and injected on a device. */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val canary = "CANARY-keystore-3b9d"

    private fun freshVault() = Vault(File(context.noBackupFilesDir, "vault-test").apply { deleteRecursively() }, KeystoreKeyProtector())

    @Test
    fun apiKeysWorkWhileUnlockedAndOnlyForTheirHost() = runBlocking {
        val vault = freshVault()
        MockWebServer().use { server ->
            server.start()
            val info = vault.put(
                NewVaultItem(
                    type = ItemType.API_KEY, label = "Test key", service = "Test", secret = canary,
                    boundHosts = setOf(server.hostName), allowedUsers = setOf("test"), protection = Protection.DEVICE_UNLOCKED,
                ),
            )
            server.enqueue(MockResponse.Builder().body("ok").build())
            val client = OkHttpClient.Builder().vaultClient(vault, requireHttps = false).build()
            val request = Request.Builder().url(server.url("/"))
                .tag(SecretUse::class.java, SecretUse(info.ref, header = "x-api-key", caller = "test")).build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertEquals(canary, server.takeRequest().headers["x-api-key"])

            val onDisk = File(context.noBackupFilesDir, "vault-test").walkTopDown().filter { it.isFile }
            assertFalse(onDisk.any { it.readText(Charsets.ISO_8859_1).contains(canary) })
        }
    }

    @Test
    fun loginsNeedAScreenLockAndSayWhy() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assumeFalse("this device has a screen lock", keyguard.isDeviceSecure)
        val vault = freshVault()
        val error = assertThrows(VaultPolicyException::class.java) {
            runBlocking { vault.put(NewVaultItem(ItemType.PASSWORD_LOGIN, "Meta", "Meta Muse", "pw")) }
        }
        assertEquals("Set a screen lock (PIN, pattern or password) first", error.message)
    }
}
