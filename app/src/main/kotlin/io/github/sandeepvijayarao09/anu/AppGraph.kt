package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.connector.phone.DoNotDisturbTool
import io.github.sandeepvijayarao09.anu.core.voice.AndroidVoiceRecorder
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import io.github.sandeepvijayarao09.anu.brain.gemini.GeminiBrain
import io.github.sandeepvijayarao09.anu.brain.litertlm.ModelArtifact
import io.github.sandeepvijayarao09.anu.brain.litertlm.ModelCatalog
import io.github.sandeepvijayarao09.anu.brain.litertlm.ModelManager
import io.github.sandeepvijayarao09.anu.brain.litertlm.ModelVariant
import io.github.sandeepvijayarao09.anu.core.model.Embedder
import io.github.sandeepvijayarao09.anu.core.model.ModelInstaller
import io.github.sandeepvijayarao09.anu.brain.embedding.EmbeddingCatalog
import io.github.sandeepvijayarao09.anu.brain.embedding.GemmaEmbedder
import io.github.sandeepvijayarao09.anu.brain.litertlm.OnDeviceBrains
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.BaseOpinion
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
import io.github.sandeepvijayarao09.anu.connector.contacts.AddContactTool
import io.github.sandeepvijayarao09.anu.connector.contacts.ProviderContacts
import io.github.sandeepvijayarao09.anu.connector.notes.AppendNoteTool
import io.github.sandeepvijayarao09.anu.connector.notes.CreateNoteTool
import io.github.sandeepvijayarao09.anu.connector.notes.SearchNotesTool
import io.github.sandeepvijayarao09.anu.core.data.RoomNoteStore
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
import io.github.sandeepvijayarao09.anu.core.data.RoomAnswerStore
import io.github.sandeepvijayarao09.anu.core.data.RoomPhotoStore
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
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.router.ComplexityScorer
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.GuardLimits
import io.github.sandeepvijayarao09.anu.core.router.HeatForecast
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.router.guardFor
import io.github.sandeepvijayarao09.anu.core.router.warmModel
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
import kotlinx.coroutines.flow.first
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import io.github.sandeepvijayarao09.anu.connector.phone.SetTimerTool
import io.github.sandeepvijayarao09.anu.connector.phone.SetAlarmTool
import io.github.sandeepvijayarao09.anu.connector.phone.BrightnessTool
import io.github.sandeepvijayarao09.anu.connector.phone.DraftTextTool
import io.github.sandeepvijayarao09.anu.connector.phone.VolumeTool
import io.github.sandeepvijayarao09.anu.connector.phone.FlashlightTool
import io.github.sandeepvijayarao09.anu.connector.phone.OpenSettingsTool
import io.github.sandeepvijayarao09.anu.connector.phone.AppPick
import io.github.sandeepvijayarao09.anu.connector.phone.CallTool
import io.github.sandeepvijayarao09.anu.connector.phone.CancelReminderTool
import io.github.sandeepvijayarao09.anu.connector.phone.Launcher
import io.github.sandeepvijayarao09.anu.connector.phone.ListRemindersTool
import io.github.sandeepvijayarao09.anu.connector.phone.NavigateTool
import io.github.sandeepvijayarao09.anu.connector.phone.OpenAppTool
import io.github.sandeepvijayarao09.anu.connector.phone.Reminders
import io.github.sandeepvijayarao09.anu.connector.phone.SetReminderTool
import io.github.sandeepvijayarao09.anu.connector.phone.ShareTool
import io.github.sandeepvijayarao09.anu.core.data.RoomReminderStore
import io.github.sandeepvijayarao09.anu.agent.adk.AdkOrchestrator
import io.github.sandeepvijayarao09.anu.connector.a2a.A2aAgentList
import io.github.sandeepvijayarao09.anu.connector.a2a.A2aAgents
import io.github.sandeepvijayarao09.anu.connector.a2a.A2aClient
import io.github.sandeepvijayarao09.anu.core.agent.AppCommands
import io.github.sandeepvijayarao09.anu.core.agent.AppTaskTool
import io.github.sandeepvijayarao09.anu.core.agent.PhoneCommands
import io.github.sandeepvijayarao09.anu.feature.settings.ThinkingSetting

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
    // Each on-device turn's speed, from the runtime's own counters, in debug builds only. Numbers only, never content.
    val onDevice = OnDeviceBrains(
        models,
        maxNumTokens = ON_DEVICE_CONTEXT,
        onTurn = if (BuildConfig.DEBUG) { id, speed -> Log.d(TAG, "Speed: ${speedLine(id, models.info(id).variant, speed)}") } else null,
    )

    /** Prompt budgets from the same context, so what the router sends to a device tier fits it (ADR-040). */
    private val budgets = TierBudgets.forContext(ON_DEVICE_CONTEXT)

    /** Token estimates learned from the on-device runtime's own counts, kept across restarts (ADR-041). */
    val tokenCalibration = TokenCalibration(
        initial = prefs.getFloat(KEY_TOKEN_RATIO, Float.NaN).takeUnless { it.isNaN() }?.toDouble(),
        onChange = { ratio -> prefs.edit { putFloat(KEY_TOKEN_RATIO, ratio.toFloat()) } },
    )
    val geminiKey = GeminiKeySettings(vault, prefs)
    val device = DeviceStateProvider(context, models, geminiKey)

    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        // What the models are doing, for debugging waits (ADR-074). Names and tiers only, never content.
        if (BuildConfig.DEBUG) scope.launch { onDevice.status.collect { Log.d(TAG, "Model: $it") } }
    }

    /**
     * Warm start (EXP-1, ADR-074): when Anu is summoned, loads the model the last answer used, or Base, so the first
     * word doesn't wait for the load. Not while the heat or battery guard is on, nor in battery saver: a load can't
     * be stopped once it starts, and a summon may end with no question.
     */
    fun warmStart() {
        if (device.current().guard != null || context.getSystemService(PowerManager::class.java).isPowerSaveMode) return
        if (!warming.compareAndSet(false, true)) return
        scope.launch {
            try {
                val last = runCatching { db.answers().latestTier() }.getOrNull()?.let { name -> Tier.entries.firstOrNull { it.name == name } }
                val id = warmModel(last, setOfNotNull(BrainId.BASE.takeIf(models::isInstalled), BrainId.PRO.takeIf(models::isInstalled)), models.baseOnTpu) ?: return@launch
                val started = System.nanoTime()
                runCatching { onDevice.warm(id) }
                // Which model and how long, never content.
                if (BuildConfig.DEBUG) Log.d(TAG, "Warm start: $id ready in ${(System.nanoTime() - started) / 1_000_000} ms")
            } finally {
                warming.set(false)
            }
        }
    }

    /** Whether the models on the phone reason before answering (G4, ADR-077): off unless the user turns it on. */
    val thinking = object : ThinkingSetting {
        private val state = MutableStateFlow(prefs.getBoolean(THINKING_KEY, false))
        override val thinking: StateFlow<Boolean> = state.asStateFlow()

        override fun setThinking(on: Boolean) {
            prefs.edit { putBoolean(THINKING_KEY, on) }
            state.value = on
        }
    }

    /** How fast each model is here: real use and Test speed, for the Models page (ADR-073). */
    val speeds by lazy { ModelSpeeds(db.answers(), onDevice, models, device, prefs) }

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

    /** Anu's keyboard: the same cascade with no cloud brain in it, so what's typed never leaves the phone (ADR-109). */
    val keyboardWriting by lazy { Cascade(router, BrainProvider { tier -> if (tier == Tier.CLOUD) null else brains.brainFor(tier) }) }

    /** Speech in and out, on the phone only (ADR-043). */
    val speechIn by lazy { AndroidSpeechInput(context).let { if (BuildConfig.DEBUG) ShellSpeech(it) else it } }

    /** Records voice notes, in memory only (G3, ADR-078). */
    val recorder by lazy { AndroidVoiceRecorder(context) }
    val speechOut by lazy { AndroidSpeechOutput(context) }

    /** Anu as the assistant, and "Hey Anu" (ADR-044, ADR-045). */
    val voice by lazy { AnuVoiceSettings(context, prefs) }

    /** Live talk behind other apps, with its microphone service and pill (ADR-108). */
    val liveTalk by lazy { LiveTalk(context) }

    /** The services and stores the user connected in Settings (ADR-046, ADR-047). */
    private val connectorSettings by lazy { RoomConnectorSettings(db.connectors()) }

    /** The user's notes, encrypted on the phone (ADR-093). */
    val notes by lazy { RoomNoteStore(db.notes()) }

    /** The user's reminders, kept encrypted until shown and set again after a reboot (ADR-107). */
    val reminders by lazy { Reminders(context, RoomReminderStore(db.reminders())) { ZonedDateTime.now(zone()) } }

    /** The phone's apps, found by name for "open Spotify" (ADR-107). */
    private val launcher by lazy { Launcher(context) }

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

    /** Agents the user added on their own network, by the Agent2Agent protocol (ADR-105). */
    val agents by lazy { A2aAgents(PrefsAgentList(prefs), A2aClient(connectorHttp), ledger) }
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
    /** Search by meaning (ADR-055), once both of its files are installed. Checked again until they are. */
    private var searchModel: Embedder? = null
    val embedder: () -> Embedder? = { searchModel ?: GemmaEmbedder.installed(File(context.noBackupFilesDir, "models"))?.also { searchModel = it } }

    /**
     * Installs a model file the user already has, if it's one this phone uses (ADR-067): a chat model's chosen build,
     * or one of search by meaning's two files, which then get their vectors at once.
     */
    val installer: ModelInstaller by lazy {
        val search = EmbeddingCatalog.MODEL.let { KnownFile(it.name, it.bytes, it.sha256, "EmbeddingGemma 300M", pairedWith = EmbeddingCatalog.TOKENIZER.name) }
        val tokenizer = EmbeddingCatalog.TOKENIZER.let { KnownFile(it.name, it.bytes, it.sha256, "EmbeddingGemma's tokenizer", pairedWith = EmbeddingCatalog.MODEL.name) }
        fun chat(artifact: ModelArtifact, what: String) = KnownFile(artifact.fileName, artifact.sizeBytes, artifact.sha256, what)
        CatalogInstaller(
            File(context.noBackupFilesDir, "models"),
            wanted = { listOf(chat(models.artifactFor(BrainId.BASE), "Gemma 4 E2B"), chat(models.artifactFor(BrainId.PRO), "Gemma 4 E4B"), search, tokenizer) },
            known = ModelCatalog.all.map { chat(it, "${if (it.brain == BrainId.BASE) "Gemma 4 E2B" else "Gemma 4 E4B"}'s ${buildName(it.variant)}") } + listOf(search, tokenizer),
            onInstalled = { file -> if (file == search || file == tokenizer) scope.launch { runCatching { memory.index() } } },
        )
    }

    val memory by lazy {
        MemoryPipeline(
            RoomMemoryStore(db),
            FactExtractor({ onDevice.memoryBrain() }),
            embedder = embedder,
        )
    }

    /** Every tool Anu has. Each skill offers only its own subset to the model (§7.3). */
    private val tools: List<Tool<*, *>> by lazy {
        val calendar = ProviderCalendar(context.contentResolver)
        val contacts = ProviderContacts(context.contentResolver)
        listOf(
            ListEventsTool(calendar, zone),
            FindFreeSlotsTool(calendar, zone),
            CreateEventTool(calendar, zone),
            FindContactTool(contacts),
            AddContactTool(contacts),
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
            // Phone controls (G6, ADR-076): simple commands run them with no model.
            SetTimerTool(context),
            SetAlarmTool(context),
            FlashlightTool(context),
            DoNotDisturbTool(context),
            OpenSettingsTool(context),
            BrightnessTool(context),
            VolumeTool(context),
            DraftTextTool(context),
            // Other apps through Android's own intents, and reminders as notifications (ADR-107).
            CallTool(context),
            OpenAppTool(context, launcher),
            NavigateTool(context),
            ShareTool(context),
            SetReminderTool(context, reminders),
            ListRemindersTool(context, reminders),
            CancelReminderTool(context, reminders),
            // Notes kept in Anu, on the phone (ADR-093).
            CreateNoteTool(notes),
            SearchNotesTool(notes),
            AppendNoteTool(notes),
        // An app task picks each step with a model call: on the TPU when Base is there, as ADK's agents do.
        ) + Flavor.tools(context, zone, appBrain = { agentBrain()?.second }, broker = { broker })
    }

    private fun toolsFor(skill: Skill): List<String> = when (skill) {
        Skill.SCHEDULING -> listOf("calendar_list_events", "calendar_find_free_slots", "calendar_create_event", "contacts_find")
        Skill.EMAIL -> listOf(
            "email_search", "email_get_thread", "email_create_draft", "email_send",
            "contacts_find", "calendar_list_events", "calendar_find_free_slots",
        )
        Skill.MESSAGES -> listOf(
            "messages_list_conversations", "messages_read_conversation", "messages_reply",
            "contacts_find", "calendar_find_free_slots", "phone_draft_text",
        ) + if (Flavor.HAS_SMS) listOf("sms_search") else emptyList()
        Skill.MEMORY -> listOf("memory_remember", "memory_search", "memory_forget")
        Skill.SHOPPING -> listOf("shop_search", "shop_checkout")
        // What a voice note of the user's own usually asks for: events and alarms (EXP-3, ADR-078). Each one is proposed and
        // approved; a shared recording gets none of these (P4). Memory isn't here: the eval saw notes stored in it as
        // lists, so only a note that starts by asking to remember gets memory's tools (ADR-085).
        Skill.VOICE_NOTE -> listOf("calendar_create_event", "calendar_find_free_slots", "contacts_find", "phone_set_alarm")
        // More than one whole command: the model gets the phone's controls, each still run by the broker (G6).
        // With notes and contacts, the device skill (ADR-093): each is one small call, so it routes to Base.
        Skill.PHONE -> listOf(
            "phone_set_timer", "phone_set_alarm", "phone_flashlight", "phone_do_not_disturb", "phone_open_settings",
            "phone_brightness", "phone_volume", "phone_draft_text",
            "phone_call", "phone_open_app", "phone_navigate", "phone_share",
            "phone_set_reminder", "phone_list_reminders", "phone_cancel_reminder",
            "notes_create", "notes_search", "notes_append", "contacts_find", "contacts_add",
        )
        // One call that names the app and the goal; the task picks each step itself (ADR-098). Personal build only.
        Skill.APPS -> if (Flavor.HAS_APP_CONTROL) listOf(AppTaskTool.NAME) else emptyList()
        else -> emptyList()
    }

    /** The text of memory's current facts among [ids], for "About this answer" (ADR-071). */
    suspend fun factTexts(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.toSet()
        return memory.state.first().facts.filter { it.id in wanted }.associate { it.id to it.text }
    }

    /** The on-device brain for app tasks' many calls (ADR-098), with its tier: Base when it's on the TPU, else Pro, else Base. */
    private fun agentBrain(): Pair<Tier, Brain>? {
        val order = if (models.baseOnTpu) listOf(Tier.BASE to BrainId.BASE, Tier.PRO to BrainId.PRO) else listOf(Tier.PRO to BrainId.PRO, Tier.BASE to BrainId.BASE)
        return order.firstNotNullOfOrNull { (tier, id) -> onDevice.brainFor(id)?.let { tier to it } }
    }

    private val broker by lazy {
        ToolBroker(tools, approvals, RoomToolResultStore(db.toolResults()), toolAudit, dynamicTools = { mcp.registry.tools() + agents.tools() })
    }

    val chat by lazy {
        ChatSession(
            // Beside Pro on the GPU, Base gives its own opinion of a request the rules gave it (ADR-106).
            agent = Agent(router, brains, broker, baseOpinion = BaseOpinion.of { onDevice.run(BrainId.BASE, it) }),
            store = RoomTranscriptStore(db.transcripts(), conversationId = "main"),
            answers = RoomAnswerStore(db.answers(), conversationId = "main"),
            photoStore = RoomPhotoStore(db.photos()),
            // A whole phone command, or a message that names an app to act in (ADR-098): each needs no model to start.
            // "Open Spotify" runs with no model only when an app has that name; anything else goes to the model (ADR-107).
            commands = { text ->
                PhoneCommands.match(text, LocalDateTime.now(zone())) { launcher.find(it) is AppPick.One }
                    ?: if (Flavor.HAS_APP_CONTROL) AppCommands.match(text) else null
            },
            // A daily brief, or a request across areas, goes to sub-agents and workflows on Google's ADK (ADR-104).
            orchestrator = AdkOrchestrator(
                broker = { broker },
                available = { (tools.map { it.name } + mcp.registry.tools().map { it.name } + agents.tools().map { it.name }).toSet() },
                // Pro first: the coordinator plans the specialists' calls. Base on the TPU is faster (ADR-103), and comes
                // after the agents are checked on Pro.
                brain = { onDevice.brainFor(BrainId.PRO) ?: onDevice.brainFor(BrainId.BASE) },
                tier = { if (onDevice.brainFor(BrainId.PRO) != null) Tier.PRO else Tier.BASE },
            ),
            thinking = { thinking.thinking.value },
            transcriber = onDevice,
            documentReader = onDevice,
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
            relevant = { text, previous ->
                val started = System.nanoTime()
                memory.relevant(text, previous).also { facts ->
                    // How long the search held the message, and what it found. Counts and times only, never content.
                    if (BuildConfig.DEBUG) {
                        val took = (System.nanoTime() - started) / 1_000_000
                        val meaning = memory.indexed()?.let { (indexed, all) -> "on, $indexed of $all facts indexed" } ?: "off"
                        Log.d(TAG, "Relevant facts: ${facts.size} in $took ms, search by meaning $meaning")
                    }
                }
            },
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

        /** The app setting that says whether models think before answering (ADR-077). */
        const val THINKING_KEY = "think_before_answering"

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

/** How the Models page names a build of a model file (ADR-019). */
internal fun buildName(variant: ModelVariant): String = when (variant) {
    ModelVariant.CPU -> "CPU build"
    ModelVariant.GPU -> "GPU build"
    ModelVariant.NPU_TENSOR_G5 -> "Tensor G5 build"
    ModelVariant.NPU_TENSOR_G6 -> "Tensor G6 build"
    ModelVariant.NPU_SNAPDRAGON_SM8750 -> "Snapdragon 8 Elite build"
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
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    /** Android's heat forecast (ENG-1): pushed by a listener on Android 16 and later, otherwise asked at most every 10 s. */
    private val heat = HeatForecast(read = { power.getThermalHeadroom(GuardLimits.FORECAST_SECONDS) }, clock = SystemClock::elapsedRealtime)

    /** Where Android says moderate throttling starts, when it says (Android 15 and later). The guard acts there. */
    @Volatile private var moderate: Float? = null

    init {
        if (Build.VERSION.SDK_INT >= 35) moderate = runCatching { power.thermalHeadroomThresholds[PowerManager.THERMAL_STATUS_MODERATE] }.getOrNull()
        if (Build.VERSION.SDK_INT >= 36) {
            // Registered for as long as the app runs: each new forecast replaces the last, and nothing is polled.
            power.addThermalHeadroomListener(context.mainExecutor) { _, forecast, _, thresholds ->
                thresholds[PowerManager.THERMAL_STATUS_MODERATE]?.let { moderate = it }
                heat.push(forecast)
            }
        }
    }

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
        // The guard (ADR-072): heat first, from the forecast or Android's severe status, then a low battery.
        val guard = guardFor(
            forecast = heat.current(),
            threshold = moderate ?: GuardLimits.HEADROOM,
            severe = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = battery.isCharging,
        )
        return DeviceState(online = online, availableTiers = tiers, guard = guard, baseOnTpu = models.baseOnTpu, proOnGpu = models.proOnGpu)
    }
}

/** The addresses of the agents the user added (ADR-105), kept in the app's settings. */
private class PrefsAgentList(private val prefs: SharedPreferences) : A2aAgentList {
    override fun addresses(): List<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().sorted()

    override fun add(address: String) {
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet()).orEmpty() + address).apply()
    }

    override fun remove(address: String) {
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet()).orEmpty() - address).apply()
    }

    private companion object {
        const val KEY = "a2a_agents"
    }
}
