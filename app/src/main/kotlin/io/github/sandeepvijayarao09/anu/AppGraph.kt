package io.github.sandeepvijayarao09.anu

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import io.github.sandeepvijayarao09.anu.brain.gemini.GeminiBrain
import io.github.sandeepvijayarao09.anu.brain.litertlm.ModelManager
import io.github.sandeepvijayarao09.anu.brain.litertlm.OnDeviceBrains
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalCenter
import io.github.sandeepvijayarao09.anu.core.agent.ContextWindow
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.connector.calendar.CreateEventTool
import io.github.sandeepvijayarao09.anu.connector.calendar.FindFreeSlotsTool
import io.github.sandeepvijayarao09.anu.connector.calendar.ListEventsTool
import io.github.sandeepvijayarao09.anu.connector.calendar.ProviderCalendar
import io.github.sandeepvijayarao09.anu.connector.contacts.FindContactTool
import io.github.sandeepvijayarao09.anu.connector.contacts.ProviderContacts
import io.github.sandeepvijayarao09.anu.connector.gmail.CreateDraftTool
import io.github.sandeepvijayarao09.anu.connector.gmail.GetThreadTool
import io.github.sandeepvijayarao09.anu.connector.gmail.GmailClient
import io.github.sandeepvijayarao09.anu.connector.gmail.PlayServicesTokens
import io.github.sandeepvijayarao09.anu.connector.gmail.SearchEmailTool
import io.github.sandeepvijayarao09.anu.connector.gmail.SendEmailTool
import io.github.sandeepvijayarao09.anu.connector.gmail.gmailHttpClient
import io.github.sandeepvijayarao09.anu.connector.messages.AnuNotificationListener
import io.github.sandeepvijayarao09.anu.connector.messages.ListConversationsTool
import io.github.sandeepvijayarao09.anu.connector.messages.MessagingHub
import io.github.sandeepvijayarao09.anu.connector.messages.ReadConversationTool
import io.github.sandeepvijayarao09.anu.connector.messages.ReplyTool
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.data.AnuDatabase
import io.github.sandeepvijayarao09.anu.core.data.RoomPrivacyLedger
import io.github.sandeepvijayarao09.anu.core.data.RoomToolAudit
import io.github.sandeepvijayarao09.anu.core.data.RoomMemoryStore
import io.github.sandeepvijayarao09.anu.core.data.RoomToolResultStore
import io.github.sandeepvijayarao09.anu.core.memory.FactExtractor
import io.github.sandeepvijayarao09.anu.core.memory.ForgetMemoryTool
import io.github.sandeepvijayarao09.anu.core.memory.MemoryPipeline
import io.github.sandeepvijayarao09.anu.core.memory.RememberTool
import io.github.sandeepvijayarao09.anu.core.memory.SearchMemoryTool
import io.github.sandeepvijayarao09.anu.core.data.RoomTranscriptStore
import io.github.sandeepvijayarao09.anu.core.data.RoomVaultAudit
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.router.ComplexityScorer
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.router.TierBudgets
import io.github.sandeepvijayarao09.anu.core.security.EgressGate
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.vault.ItemType
import io.github.sandeepvijayarao09.anu.core.vault.KeystoreKeyProtector
import io.github.sandeepvijayarao09.anu.core.vault.NewVaultItem
import io.github.sandeepvijayarao09.anu.core.vault.Protection
import io.github.sandeepvijayarao09.anu.core.vault.Vault
import io.github.sandeepvijayarao09.anu.core.vault.withVault
import io.github.sandeepvijayarao09.anu.feature.settings.AccountConnection
import io.github.sandeepvijayarao09.anu.feature.settings.CloudKeySettings
import io.github.sandeepvijayarao09.anu.feature.settings.NotificationAccess
import io.github.sandeepvijayarao09.anu.feature.settings.VaultActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.time.ZoneId

/**
 * The app's object graph, wired by hand (ADR-023). This is the only place concrete brains, the vault
 * and connectors meet contracts (§4.2).
 */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences = context.getSharedPreferences("anu", Context.MODE_PRIVATE)

    /** Encrypted with a Keystore-wrapped key (ADR-024). Chats, the Privacy Ledger and the vault audit live here. */
    val db: AnuDatabase by lazy { AnuDatabase.open(context) }
    private val vaultAudit by lazy { RoomVaultAudit(db.vaultAudit()) }

    val vault = Vault(File(context.noBackupFilesDir, "vault"), KeystoreKeyProtector(), audit = { ref, caller, host, at ->
        vaultAudit.used(ref, caller, host, at)
    })
    val ledger: PrivacyLedger by lazy { RoomPrivacyLedger(db.ledger(), scope) }
    private val egress by lazy { EgressGate(ledger) }

    val models = ModelManager(context)
    val onDevice = OnDeviceBrains(models, maxNumTokens = ON_DEVICE_CONTEXT)

    /** Prompt budgets from the same context, so what the router sends to a device tier fits it (ADR-040). */
    private val budgets = TierBudgets.forContext(ON_DEVICE_CONTEXT)
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

    /** Pending approvals, shown by the chat screen (I2). */
    val approvals = ApprovalCenter()
    private val toolAudit by lazy { RoomToolAudit(db.toolAudit()) }
    private val zone: () -> ZoneId = ZoneId::systemDefault

    /** Gmail tokens come from Play services on demand. Anu stores none (ADR-029). */
    val gmailAccess = PlayServicesTokens(context)

    /** The settings screen's view of Gmail, so `feature:settings` never depends on the connector (§4.2). */
    val gmailConnection = object : AccountConnection {
        override suspend fun isConnected() = gmailAccess.isConnected()
        override suspend fun beginConnect() = gmailAccess.beginConnect()
        override fun finishConnect(data: Intent?) = gmailAccess.finishConnect(data)
    }
    private val gmail by lazy { GmailClient(gmailHttpClient(), gmailAccess, ledger) }

    /** Messaging apps reach Anu through their notifications, once the user allows it in Android's settings (ADR-031). */
    val messagingAccess = object : NotificationAccess {
        override fun isGranted() = AnuNotificationListener.isEnabled(context)
        override fun settingsIntents() = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, AnuNotificationListener.component(context).flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
    }

    /**
     * Memory (§8): learned on the phone only, from the user's own words, with an on-device model. The
     * smaller model is used when it's installed.
     */
    val memory by lazy {
        MemoryPipeline(RoomMemoryStore(db), FactExtractor({ onDevice.brainFor(BrainId.BASE) ?: onDevice.brainFor(BrainId.PRO) }))
    }

    /** Every tool Anu has. Each skill offers only its own subset to the model (§7.3). */
    private val tools: List<Tool<*, *>> by lazy {
        val calendar = ProviderCalendar(context.contentResolver)
        listOf(
            ListEventsTool(calendar, zone),
            FindFreeSlotsTool(calendar, zone),
            CreateEventTool(calendar, zone),
            FindContactTool(ProviderContacts(context.contentResolver)),
            SearchEmailTool(gmail),
            GetThreadTool(gmail),
            CreateDraftTool(gmail),
            SendEmailTool(gmail),
            ListConversationsTool(MessagingHub.store, zone),
            ReadConversationTool(MessagingHub.store, zone),
            ReplyTool(MessagingHub.store),
            RememberTool(memory),
            SearchMemoryTool(memory),
            ForgetMemoryTool(memory),
        ) + Flavor.tools(context, zone)
    }

    private fun toolsFor(skill: Skill): List<String> = when (skill) {
        Skill.SCHEDULING -> listOf("calendar_list_events", "calendar_find_free_slots", "calendar_create_event", "contacts_find")
        Skill.EMAIL -> listOf(
            "email_search", "email_get_thread", "email_create_draft", "email_send",
            "contacts_find", "calendar_list_events", "calendar_find_free_slots",
        )
        Skill.MESSAGES -> listOf(
            "messages_list_conversations", "messages_read_conversation", "messages_reply",
            "contacts_find", "calendar_find_free_slots",
        ) + if (Flavor.HAS_SMS) listOf("sms_search") else emptyList()
        Skill.MEMORY -> listOf("memory_remember", "memory_search", "memory_forget")
        else -> emptyList()
    }

    private val broker by lazy { ToolBroker(tools, approvals, RoomToolResultStore(db.toolResults()), toolAudit) }

    val chat by lazy {
        ChatSession(
            agent = Agent(Router(ComplexityScorer(budgets)), brains, broker),
            store = RoomTranscriptStore(db.transcripts(), conversationId = "main"),
            toolsFor = ::toolsFor,
            memory = {
                // The chat carries on without memory if it fails. The log names the failure, never the content.
                try {
                    memory.memoryBlock()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Memory block unavailable", e)
                    null
                }
            },
            onTurnCompleted = { text, turnKey -> memory.captureChat(text, turnKey) },
            window = ContextWindow(budget = budgets.pro),
        ).also { session ->
            // How much of the chat the model sees. Sizes only, never content.
            if (BuildConfig.DEBUG) {
                scope.launch {
                    session.context.collect { Log.d(TAG, "Context: from message ${it.shownFrom} of ${it.messages}, ~${it.tokens} of ${it.budget} tokens") }
                }
            }
        }
    }

    /** What Anu did with tools, newest first, for the settings screen (§7.2). */
    val toolActivity: Flow<List<ToolAuditEntry>> by lazy { toolAudit.recent() }

    /** Recent uses of vault secrets, with item labels, for the settings screen (§11). */
    val vaultActivity: Flow<List<VaultActivity>> by lazy {
        vaultAudit.recent().map { uses ->
            val labels = vault.list().associate { it.id to it.label }
            uses.map { VaultActivity(labels[it.itemId] ?: "Deleted item", it.caller, it.host, it.at) }
        }
    }

    private companion object {
        const val TAG = "Anu"

        /** The on-device engines' context, in tokens. S1 on the reference phone may raise it (ADR-040). */
        const val ON_DEVICE_CONTEXT = 4_096

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
