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
import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.agent.ContextWindow
import io.github.sandeepvijayarao09.anu.core.agent.TokenCalibration
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
import io.github.sandeepvijayarao09.anu.core.voice.AndroidSpeechInput
import io.github.sandeepvijayarao09.anu.core.voice.AndroidSpeechOutput
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
import io.github.sandeepvijayarao09.anu.connector.mcp.McpClient
import io.github.sandeepvijayarao09.anu.connector.mcp.McpRegistry
import io.github.sandeepvijayarao09.anu.connector.mcp.McpTool
import io.github.sandeepvijayarao09.anu.connector.shopping.SeenProducts
import io.github.sandeepvijayarao09.anu.connector.shopping.ShopCheckoutTool
import io.github.sandeepvijayarao09.anu.connector.shopping.ShopSearchTool
import io.github.sandeepvijayarao09.anu.connector.shopping.UcpClient
import io.github.sandeepvijayarao09.anu.core.data.RoomConnectorSettings
import io.github.sandeepvijayarao09.anu.core.security.SecretUse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
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

    /** Token estimates learned from the on-device runtime's own counts, kept across restarts (ADR-041). */
    val tokenCalibration = TokenCalibration(
        initial = prefs.getFloat(KEY_TOKEN_RATIO, Float.NaN).takeUnless { it.isNaN() }?.toDouble(),
        onChange = { ratio -> prefs.edit { putFloat(KEY_TOKEN_RATIO, ratio.toFloat()) } },
    )
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

    /** One router for chat and writing, with budgets from the on-device context (ADR-040). */
    private val router by lazy { Router(ComplexityScorer(budgets)) }

    /** Writing from any app's text-selection menu: the cascade with no tools (ADR-042). */
    val writing by lazy { Cascade(router, brains) }

    /** Speech in and out, on the phone only (ADR-043). */
    val speechIn by lazy { AndroidSpeechInput(context) }
    val speechOut by lazy { AndroidSpeechOutput(context) }

    /** Anu as the assistant, and "Hey Anu" (ADR-044, ADR-045). */
    val voice by lazy { AnuVoiceSettings(context, prefs) }

    /** The services and stores the user connected in Settings (ADR-046, ADR-047). */
    private val connectorSettings by lazy { RoomConnectorSettings(db.connectors()) }

    /**
     * For MCP servers and stores. The vault attaches a server's token for its bound host only, and
     * never over plain HTTP. Tool calls on a server can take a while.
     */
    private val connectorHttp: OkHttpClient by lazy {
        OkHttpClient.Builder().withVault(vault).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    }

    /** Plain HTTP reaches only the Mac's test servers, and only in debug builds (see `scripts/dev-mock-*.py`). */
    private val localTestHost: (HttpUrl) -> Boolean = { url -> BuildConfig.DEBUG && url.host in DEV_HOSTS }

    /** MCP servers: their tools join a turn when the user names the server (ADR-046). */
    val mcp by lazy {
        val registry = McpRegistry(
            clientFor = { server ->
                McpClient(
                    connectorHttp,
                    server.url.toHttpUrl(),
                    BuildConfig.VERSION_NAME,
                    server.token?.let { SecretUse(it, header = "Authorization", caller = McpTool.CALLER, scheme = "Bearer") },
                )
            },
            ledger = ledger,
        )
        McpConnections(connectorSettings, vault, registry, scope, localTestHost)
    }

    /** Stores that speak UCP (ADR-047). */
    private val ucp by lazy { UcpClient(connectorHttp, UCP_PROFILE, localTestHost) }
    val stores by lazy { StoreConnections(connectorSettings, ucp, scope) }
    private val seenProducts = SeenProducts()

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
            ShopSearchTool(stores::all, ucp, seenProducts, ledger),
            ShopCheckoutTool(
                stores::all,
                ucp,
                seenProducts,
                ledger,
                linkAllowed = { link -> link.toHttpUrlOrNull()?.let { it.isHttps || localTestHost(it) } == true },
            ),
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
        Skill.SHOPPING -> listOf("shop_search", "shop_checkout")
        else -> emptyList()
    }

    private val broker by lazy {
        ToolBroker(tools, approvals, RoomToolResultStore(db.toolResults()), toolAudit, dynamicTools = { mcp.registry.tools() })
    }

    val chat by lazy {
        ChatSession(
            agent = Agent(router, brains, broker),
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
            tokens = tokenCalibration,
            extraTools = { turns -> mcp.registry.toolsFor(turns) },
            relevant = { text -> memory.relevant(text) },
        ).also { session ->
            // How much of the chat the model sees, and how the estimates compare. Sizes only, never content.
            if (BuildConfig.DEBUG) {
                scope.launch {
                    session.context.collect { view ->
                        val sample = view.lastSample?.let { " · last turn estimated ${it.estimated}, runtime counted ${it.counted}" }.orEmpty()
                        Log.d(TAG, "Context: from message ${view.shownFrom} of ${view.messages}, ~${view.tokens} of ${view.budget} tokens, ${view.relevantFacts} relevant facts, scale ${"%.2f".format(view.scale)}$sample")
                    }
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

        const val KEY_TOKEN_RATIO = "token_ratio"

        /** An alias, so the ID doesn't go stale (ADR-022). It becomes a setting once S8 confirms it. */
        const val GEMINI_MODEL = "gemini-flash-latest"

        /**
         * UCP asks every request to name the platform's profile. Anu has no server to host one yet, so this
         * `.invalid` address says so honestly (ADR-047). Stores that require a fetchable profile won't work
         * until a hosted profile exists (Planned).
         */
        const val UCP_PROFILE = "https://anu.invalid/ucp/profile.json"

        /** Where the emulator reaches the Mac's test servers. */
        val DEV_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")
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
