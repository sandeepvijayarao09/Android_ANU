package io.github.sandeepvijayarao09.anu

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.edit
import io.github.sandeepvijayarao09.anu.brain.gemini.GeminiBrain
import io.github.sandeepvijayarao09.anu.brain.litertlm.ModelManager
import io.github.sandeepvijayarao09.anu.brain.litertlm.OnDeviceBrains
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.security.EgressGate
import io.github.sandeepvijayarao09.anu.core.security.InMemoryLedger
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.vault.ItemType
import io.github.sandeepvijayarao09.anu.core.vault.KeystoreKeyProtector
import io.github.sandeepvijayarao09.anu.core.vault.NewVaultItem
import io.github.sandeepvijayarao09.anu.core.vault.Protection
import io.github.sandeepvijayarao09.anu.core.vault.Vault
import io.github.sandeepvijayarao09.anu.core.vault.withVault
import io.github.sandeepvijayarao09.anu.feature.settings.CloudKeySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File

/**
 * The app's object graph, wired by hand (ADR-023). This is the only place concrete brains, the vault
 * and connectors meet contracts (§4.2).
 */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences = context.getSharedPreferences("anu", Context.MODE_PRIVATE)

    val vault = Vault(File(context.noBackupFilesDir, "vault"), KeystoreKeyProtector())
    val ledger: PrivacyLedger = InMemoryLedger()
    private val egress = EgressGate(ledger)

    val models = ModelManager(context)
    val onDevice = OnDeviceBrains(models)
    val geminiKey = GeminiKeySettings(vault, prefs)
    val device = DeviceStateProvider(context, models, geminiKey)

    private val vaultHttp: OkHttpClient by lazy { OkHttpClient.Builder().withVault(vault).build() }

    private val brains = BrainProvider { tier ->
        when (tier) {
            Tier.BASE -> onDevice.brainFor(BrainId.BASE)
            Tier.PRO -> onDevice.brainFor(BrainId.PRO)
            Tier.CLOUD -> geminiKey.ref()?.let { GeminiBrain(BrainId.CLOUD_FLASH, GEMINI_MODEL, it, vaultHttp, egress) }
        }
    }

    val chat = ChatSession(Cascade(Router(), brains))

    private companion object {
        /** An alias, so the ID doesn't go stale (ADR-022). It becomes a setting once S8 confirms it. */
        const val GEMINI_MODEL = "gemini-flash-latest"
    }
}

/** Saves the Gemini key into the vault, bound to Google's API host and to the Gemini brain alone (§11). */
class GeminiKeySettings(private val vault: Vault, private val prefs: SharedPreferences) : CloudKeySettings {
    private val present = MutableStateFlow(ref() != null)
    override val hasKey: StateFlow<Boolean> = present.asStateFlow()

    fun ref(): SecretRef? = prefs.getString(KEY_REF, null)?.let(::SecretRef)

    override suspend fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "The key is empty" }
        ref()?.let { vault.delete(it) }
        val info = vault.put(
            NewVaultItem(
                type = ItemType.API_KEY,
                label = "Gemini API key",
                service = "Google AI Studio",
                secret = apiKey.trim(),
                boundHosts = setOf(GEMINI_HOST),
                allowedUsers = setOf(GeminiBrain.CALLER),
                // Unlocked-device level: background features such as the brief can use it without a prompt.
                protection = Protection.DEVICE_UNLOCKED,
            ),
        )
        prefs.edit { putString(KEY_REF, info.id) }
        present.value = true
    }

    override suspend fun remove() {
        ref()?.let { vault.delete(it) }
        prefs.edit { remove(KEY_REF) }
        present.value = false
    }

    private companion object {
        const val KEY_REF = "gemini_key_ref"
        const val GEMINI_HOST = "generativelanguage.googleapis.com"
    }
}

/** Stage A inputs for the router (§6): network, installed tiers, heat and battery. */
class DeviceStateProvider(
    private val context: Context,
    private val models: ModelManager,
    private val geminiKey: GeminiKeySettings,
) {
    fun current(): DeviceState {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val tiers = buildSet {
            if (models.isInstalled(BrainId.BASE)) add(Tier.BASE)
            if (models.isInstalled(BrainId.PRO)) add(Tier.PRO)
            if (geminiKey.ref() != null) add(Tier.CLOUD)
        }
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val hot = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val low = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..15 && !battery.isCharging
        return DeviceState(online = online, availableTiers = tiers, constrained = hot || low)
    }
}
