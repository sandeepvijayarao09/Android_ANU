package io.github.sandeepvijayarao09.anu.feature.settings

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelInfo
import io.github.sandeepvijayarao09.anu.core.model.ModelInstaller
import io.github.sandeepvijayarao09.anu.core.model.ModelSpeed
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.model.TestStep
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.model.OnDeviceModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.time.Instant

/** The Gemini key as the settings screen sees it. The key goes straight into the vault and is never shown again (§11). */
interface CloudKeySettings {
    val hasKey: StateFlow<Boolean>

    suspend fun save(apiKey: String)

    suspend fun remove()
}

/**
 * Gemma on the user's own Mac as the top model, in Gemini's place (ADR-111). [paired] is the Mac's address, or null.
 * The pairing code holds a token, which goes straight into the vault and is never shown again (§11).
 */
interface MacSettings {
    val paired: StateFlow<String?>

    /** False when [code] isn't a pairing code. */
    suspend fun pair(code: String): Boolean

    suspend fun unpair()
}

/**
 * A model on the Models page, with the space its files take now (ADR-061) and the phone's memory, which a model
 * may need more of (ADR-066).
 */
data class ModelRow(val info: ModelInfo, val state: ModelState, val spaceUsed: Long = 0, val deviceMemoryGb: Int = 0)

/** A file being checked and installed from the phone's storage (ADR-067). */
data class FileInstall(val done: Long, val total: Long)

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

/** Whether the models on the phone reason before answering (G4, ADR-077). Off unless the user chooses it. */
interface ThinkingSetting {
    val thinking: StateFlow<Boolean>

    fun setThinking(on: Boolean)
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

/** A model's sheet on the Models page (design review 2C, ADR-073). */
data class ModelSheet(
    val id: BrainId,
    /** The middle of the user's last answers on this model, or null for none. */
    val realUse: Speed? = null,
    val realUseLoaded: Boolean = false,
    val lastTest: Speed? = null,
    /** The phone is warm now, so a test wouldn't be comparable (ADR-072). */
    val warm: Boolean = false,
    val test: TestState = TestState.Idle,
)

/** Where Test speed is (design review D12). */
sealed interface TestState {
    data object Idle : TestState

    /** [speed] is what was measured so far. */
    data class Running(val step: TestStep, val speed: Speed?) : TestState

    /** Stopped by the user: partial numbers, marked. */
    data class Stopped(val speed: Speed?) : TestState

    data class Failed(val reason: String) : TestState
}

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
    /** Whether search by meaning's model is installed (ADR-055). Null when the app has no search by meaning. */
    private val searchByMeaning: (() -> Boolean)? = null,
    /** Installs a model file the user already has (ADR-067). Null where the app can't. */
    private val installer: ModelInstaller? = null,
    /** Real use and Test speed for each model's sheet (ADR-073). Null where the app has none. */
    private val speeds: ModelSpeed? = null,
    /** Thinking before answering (ADR-077). Null where the app has none. */
    private val thinkingSetting: ThinkingSetting? = null,
    /** The user's Mac as the top model (ADR-111). Null where the app has none. */
    private val mac: MacSettings? = null,
) : ViewModel() {

    val thinking: StateFlow<Boolean> = thinkingSetting?.thinking ?: MutableStateFlow(false)
    val canThink: Boolean get() = thinkingSetting != null

    fun setThinking(on: Boolean) {
        thinkingSetting?.setThinking(on)
    }

    private val mutableSheet = MutableStateFlow<ModelSheet?>(null)

    /** The open model's sheet (design review 2C), with its speeds and any test in progress. */
    val modelSheet: StateFlow<ModelSheet?> = mutableSheet.asStateFlow()

    private var testing: Job? = null

    fun openModel(id: BrainId) {
        mutableSheet.value = ModelSheet(id, lastTest = speeds?.lastTest(id), warm = speeds?.warm() ?: false)
        val speeds = speeds ?: return
        viewModelScope.launch {
            val realUse = runCatching { speeds.realUse(id) }.getOrNull()
            mutableSheet.value = mutableSheet.value?.takeIf { it.id == id }?.copy(realUse = realUse, realUseLoaded = true)
        }
    }

    /** Closing the sheet stops a test that is running: its numbers would have nowhere to show. */
    fun closeModel() {
        testing?.cancel()
        mutableSheet.value = null
    }

    /** Test speed (design review D12): each step shows as it happens; the result replaces the last test. */
    fun testSpeed() {
        val sheet = mutableSheet.value ?: return
        val speeds = speeds ?: return
        if (testing?.isActive == true) return
        mutableSheet.value = sheet.copy(test = TestState.Running(TestStep.WAITING, null), warm = speeds.warm())
        testing = viewModelScope.launch {
            try {
                speeds.test(sheet.id).collect { update ->
                    mutableSheet.value = mutableSheet.value?.let { open ->
                        if (update.step == TestStep.DONE) {
                            open.copy(test = TestState.Idle, lastTest = update.speed ?: open.lastTest, warm = speeds.warm())
                        } else {
                            open.copy(test = TestState.Running(update.step, update.speed))
                        }
                    }
                }
            } catch (e: CancellationException) {
                mutableSheet.value = mutableSheet.value?.let { open -> open.copy(test = TestState.Stopped((open.test as? TestState.Running)?.speed)) }
                throw e
            } catch (e: Exception) {
                mutableSheet.value = mutableSheet.value?.copy(test = TestState.Failed(e.message ?: "the model didn't answer"))
            }
        }
    }

    fun stopTest() {
        testing?.cancel()
    }

    private val mutableSearch = MutableStateFlow(searchByMeaning?.invoke())
    val searchInstalled: StateFlow<Boolean?> = mutableSearch.asStateFlow()

    /** The model can also be installed from a computer, so check again when the page shows. */
    fun refreshSearch() {
        mutableSearch.value = searchByMeaning?.invoke()
    }

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
            listOf(BrainId.BASE to base, BrainId.PRO to pro).map { (id, state) ->
                ModelRow(models.info(id), state, models.spaceUsed(id), models.deviceMemoryGb)
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Re-reads the models, whose space grows when one first runs and writes its weight cache (ADR-061). */
    fun refreshModels() {
        refresh.value++
    }

    val hasCloudKey: StateFlow<Boolean> = cloudKey.hasKey
    val toolActions: StateFlow<List<ToolAuditEntry>> =
        toolActivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val vaultUses: StateFlow<List<VaultActivity>> =
        vaultActivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    fun download(id: BrainId) = models.download(id)

    val canInstallFiles: Boolean get() = installer != null
    private val mutableInstalling = MutableStateFlow<FileInstall?>(null)
    val installing: StateFlow<FileInstall?> = mutableInstalling.asStateFlow()

    /** Checks and installs a file the user chose; [size] is what its provider reported, if anything. */
    fun install(size: Long?, open: () -> InputStream) {
        val installer = installer ?: return
        if (mutableInstalling.value != null) return
        mutableInstalling.value = FileInstall(0, size ?: 0)
        viewModelScope.launch {
            try {
                val outcome = installer.install(size, open) { done, total -> mutableInstalling.value = FileInstall(done, total) }
                mutableMessage.value = installMessage(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableMessage.value = "Couldn't read that file: ${e.message}"
            } finally {
                mutableInstalling.value = null
                refresh.value++
                refreshSearch()
            }
        }
    }

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

    val macPaired: StateFlow<String?> = mac?.paired ?: MutableStateFlow(null)
    val canPairMac: Boolean get() = mac != null

    fun pairMac(code: String) = viewModelScope.launch {
        val mac = mac ?: return@launch
        mutableMessage.value = runCatching { mac.pair(code) }
            .fold({ if (it) "Paired. Hard requests now go to your Mac." else "That isn't a pairing code from mac-brain.py." }, { "Couldn't pair: ${it.message}" })
    }

    fun unpairMac() = viewModelScope.launch {
        mac?.unpair()
        mutableMessage.value = "Your Mac is no longer used."
    }

    fun dismissMessage() {
        mutableMessage.value = null
    }
}
