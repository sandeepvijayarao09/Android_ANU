package io.github.sandeepvijayarao09.anu.feature.settings

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelInfo
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.model.OnDeviceModels
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** The Gemini key as the settings screen sees it. The key goes straight into the vault and is never shown again (§11). */
interface CloudKeySettings {
    val hasKey: StateFlow<Boolean>

    suspend fun save(apiKey: String)

    suspend fun remove()
}

data class ModelRow(val info: ModelInfo, val state: ModelState)

/** An account connected through the provider's own consent screen, such as Gmail (§9). */
interface AccountConnection {
    suspend fun isConnected(): Boolean

    /** Returns the consent screen to launch, or null when access is already granted. */
    suspend fun beginConnect(): IntentSender?

    fun finishConnect(data: Intent?): Boolean
}

/**
 * Notification access for the messaging connector. Only Android's own settings can grant it, never a
 * dialog, so the screen sends the user there (ADR-031).
 */
interface NotificationAccess {
    fun isGranted(): Boolean

    /** Android's settings pages for the access, most specific first: some phones lack the detail page. */
    fun settingsIntents(): List<Intent>
}

/** One use of a vault secret, as the audit trail records it: never the value (§11). */
data class VaultActivity(val label: String, val caller: String, val host: String, val at: Instant)

class SettingsViewModel(
    private val models: OnDeviceModels,
    private val cloudKey: CloudKeySettings,
    ledger: PrivacyLedger,
    vaultActivity: Flow<List<VaultActivity>>,
    toolActivity: Flow<List<ToolAuditEntry>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    private val gmail: AccountConnection? = null,
    private val messaging: NotificationAccess? = null,
    /** SMS is only in the `personal` build (ADR-012, ADR-032). */
    val smsAvailable: Boolean = false,
) : ViewModel() {

    /** Null when there is no messaging connector. Re-checked with [refreshMessaging] when the screen resumes. */
    private val mutableMessaging = MutableStateFlow(messaging?.isGranted())
    val messagingEnabled: StateFlow<Boolean?> = mutableMessaging.asStateFlow()

    fun refreshMessaging() {
        mutableMessaging.value = messaging?.isGranted()
    }

    fun messagingSettings(): List<Intent> = messaging?.settingsIntents().orEmpty()

    /** Null while checking. */
    private val mutableGmail = MutableStateFlow<Boolean?>(null)
    val gmailConnected: StateFlow<Boolean?> = mutableGmail.asStateFlow()

    /** Set when the consent screen must be shown. The screen launches it, then calls [finishGmail]. */
    private val mutableConsent = MutableStateFlow<IntentSender?>(null)
    val gmailConsent: StateFlow<IntentSender?> = mutableConsent.asStateFlow()

    init {
        gmail?.let { account -> viewModelScope.launch { mutableGmail.value = runCatching { account.isConnected() }.getOrDefault(false) } }
    }

    fun connectGmail() {
        val account = gmail ?: return
        viewModelScope.launch {
            runCatching { account.beginConnect() }
                .onSuccess { consent -> if (consent == null) mutableGmail.value = true else mutableConsent.value = consent }
                .onFailure { mutableMessage.value = "Gmail couldn't connect: ${it.message}. Check the Google Cloud setup in docs/GMAIL_SETUP.md." }
        }
    }

    fun finishGmail(data: Intent?) {
        mutableConsent.value = null
        val connected = gmail?.finishConnect(data) == true
        mutableGmail.value = connected
        if (!connected) mutableMessage.value = "Gmail wasn't connected."
    }

    // WorkManager doesn't emit when a file is deleted, so a delete bumps this to re-read the state.
    private val refresh = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val modelRows: StateFlow<List<ModelRow>> = refresh.flatMapLatest {
        combine(models.state(BrainId.BASE), models.state(BrainId.PRO)) { base, pro ->
            listOf(ModelRow(models.info(BrainId.BASE), base), ModelRow(models.info(BrainId.PRO), pro))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasCloudKey: StateFlow<Boolean> = cloudKey.hasKey
    val ledgerEntries: StateFlow<List<LedgerEntry>> = ledger.entries
    val toolActions: StateFlow<List<ToolAuditEntry>> =
        toolActivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val vaultUses: StateFlow<List<VaultActivity>> =
        vaultActivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    fun download(id: BrainId) = models.download(id)

    fun delete(id: BrainId) {
        models.delete(id)
        refresh.value++
    }

    fun saveCloudKey(key: String) = viewModelScope.launch {
        mutableMessage.value = runCatching { cloudKey.save(key) }
            .fold({ "Key saved to the vault." }, { "Couldn't save the key: ${it.message}" })
    }

    fun removeCloudKey() = viewModelScope.launch {
        cloudKey.remove()
        mutableMessage.value = "Key removed. Anu will stay on this phone."
    }

    fun dismissMessage() {
        mutableMessage.value = null
    }
}
