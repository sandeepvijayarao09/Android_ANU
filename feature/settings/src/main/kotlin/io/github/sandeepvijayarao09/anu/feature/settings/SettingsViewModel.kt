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

/**
 * Anu as the phone's assistant, and "Hey Anu" (ADR-044, ADR-045). Android decides the assistant, so the
 * screen sends the user to Android's page for it.
 */
interface VoiceSettings {
    fun isAssistant(): Boolean

    /** Android's pages for choosing the assistant, most specific first. */
    fun assistantSettingsIntents(): List<Intent>

    /** Whether Anu listens for "Hey Anu" while the screen is on. */
    val wakeWord: StateFlow<Boolean>

    fun setWakeWord(enabled: Boolean)
}

/** An MCP server as Settings shows it (ADR-046). */
data class McpServerRow(
    val id: String,
    val name: String,
    val host: String,
    val tools: List<String>,
    val error: String?,
    val hasToken: Boolean,
    /** False until its tools have been fetched once in this run of the app. */
    val loaded: Boolean = true,
)

/** The user's MCP servers. A token goes straight into the vault, bound to the server's host, and is never shown again (§11). */
interface McpServerSettings {
    val servers: StateFlow<List<McpServerRow>>

    /** Saves the server and fetches its tools. Returns it as fetched; throws with a reason the user can act on. */
    suspend fun add(name: String, url: String, token: String?): McpServerRow

    suspend fun refresh(id: String)

    /** Fetches the tools of servers not fetched yet in this run of the app, for the list to show them. */
    suspend fun loadMissing()

    /** Removes the server, and its token from the vault. */
    suspend fun remove(id: String)
}

/** A store as Settings shows it (ADR-047). */
data class StoreRow(val address: String, val name: String, val endpointHost: String, val canSearch: Boolean, val canCheckout: Boolean)

/** Stores that speak UCP, added by their web address. */
interface StoreSettings {
    val stores: StateFlow<List<StoreRow>>

    /** Reads the store's UCP profile and saves it. Throws with a reason the user can act on. */
    suspend fun add(address: String): StoreRow

    suspend fun remove(address: String)
}

/** One use of a vault secret, as the audit trail records it: never the value (§11). */
data class VaultActivity(val label: String, val caller: String, val host: String, val at: Instant)

class SettingsViewModel(
    private val models: OnDeviceModels,
    private val cloudKey: CloudKeySettings,
    vaultActivity: Flow<List<VaultActivity>>,
    toolActivity: Flow<List<ToolAuditEntry>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    private val gmail: AccountConnection? = null,
    private val messaging: NotificationAccess? = null,
    /** SMS is only in the `personal` build (ADR-012, ADR-032). */
    val smsAvailable: Boolean = false,
    private val voice: VoiceSettings? = null,
    private val mcp: McpServerSettings? = null,
    private val shopping: StoreSettings? = null,
) : ViewModel() {

    val mcpAvailable: Boolean get() = mcp != null
    val mcpServers: StateFlow<List<McpServerRow>> = mcp?.servers ?: MutableStateFlow(emptyList())
    val shoppingAvailable: Boolean get() = shopping != null
    val stores: StateFlow<List<StoreRow>> = shopping?.stores ?: MutableStateFlow(emptyList())

    /** True while a server or store is being reached for the first time. */
    private val mutableConnecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = mutableConnecting.asStateFlow()

    fun addMcpServer(name: String, url: String, token: String) = connect("Couldn't add the server") {
        val added = mcp?.add(name.trim(), url.trim(), token.trim().ifEmpty { null }) ?: return@connect null
        when {
            added.error != null -> "${added.name} was added, but: ${added.error}"
            else -> "${added.name} is connected, with ${added.tools.size} tools. Name it in a request to use them."
        }
    }

    fun refreshMcpServer(id: String) = connect("Couldn't reach the server") { mcp?.refresh(id); null }

    fun loadMcpTools() {
        viewModelScope.launch { runCatching { mcp?.loadMissing() } }
    }

    fun removeMcpServer(id: String) = connect("Couldn't remove the server") { mcp?.remove(id); null }

    fun addStore(address: String) = connect("Couldn't add the store") {
        val store = shopping?.add(address.trim()) ?: return@connect null
        "${store.name} is added. Ask Anu to find or buy something there."
    }

    fun removeStore(address: String) = connect("Couldn't remove the store") { shopping?.remove(address); null }

    private fun connect(failure: String, block: suspend () -> String?) = viewModelScope.launch {
        mutableConnecting.value = true
        try {
            runCatching { block() }
                .onSuccess { done -> done?.let { mutableMessage.value = it } }
                .onFailure { mutableMessage.value = "$failure: ${it.message ?: it::class.simpleName}" }
        } finally {
            mutableConnecting.value = false
        }
    }

    /** Null when there are no voice settings. Re-checked with [refreshAssistant] when the screen resumes. */
    private val mutableAssistant = MutableStateFlow(voice?.isAssistant())
    val isAssistant: StateFlow<Boolean?> = mutableAssistant.asStateFlow()

    fun refreshAssistant() {
        mutableAssistant.value = voice?.isAssistant()
    }

    fun assistantSettings(): List<Intent> = voice?.assistantSettingsIntents().orEmpty()

    val wakeWord: StateFlow<Boolean> = voice?.wakeWord ?: MutableStateFlow(false)

    fun setWakeWord(enabled: Boolean) {
        voice?.setWakeWord(enabled)
    }

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
