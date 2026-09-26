package io.github.sandeepvijayarao09.anu.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalCenter
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.ToolOutcome
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.voice.Sentences
import io.github.sandeepvijayarao09.anu.core.voice.SpeechEvent
import io.github.sandeepvijayarao09.anu.core.voice.SpeechInput
import io.github.sandeepvijayarao09.anu.core.voice.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

data class Bubble(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val tier: Tier? = null,
    /** Status shown under the text, for example "Trying Pro…". */
    val note: String? = null,
    val streaming: Boolean = false,
    val failed: Boolean = false,
    /** What really happened, from the Tool Broker's record, never from the model's words (ADR-027). */
    val actions: List<String> = emptyList(),
    /** Links a tool offered, such as a store's checkout page. Shown as buttons; only the user opens them (ADR-047). */
    val links: List<LinkAction> = emptyList(),
)

data class LinkAction(val label: String, val url: String)

data class ChatUiState(
    val bubbles: List<Bubble> = emptyList(),
    val busy: Boolean = false,
    /** Set when a message needs cloud consent (§12.2). Holds the text waiting to be sent. */
    val consentFor: String? = null,
    /** The oldest action waiting for the user's approval (I2). */
    val approval: ApprovalRequest? = null,
    /** True once the chat is longer than the model's view, so older messages are left out (ADR-040). */
    val trimmed: Boolean = false,
    /** Voice (ADR-043): whether Anu is listening, and what it has heard so far. */
    val listening: Boolean = false,
    val heard: String = "",
    /** How loud the user is, from 0 to 1, for the orb. */
    val level: Float = 0f,
    val speaking: Boolean = false,
    val muted: Boolean = false,
    /** Why voice didn't work this time, such as "Didn't catch that." */
    val voiceNote: String? = null,
    /** The on-device recognizer needs this language's model; the user can choose to get it. */
    val offerLanguagePack: Boolean = false,
    /** False when the phone has no on-device speech recognition. */
    val voiceAvailable: Boolean = false,
) {
    /** What the orb shows. */
    val voice: VoicePhase
        get() = when {
            listening -> VoicePhase.LISTENING
            speaking -> VoicePhase.SPEAKING
            busy -> VoicePhase.THINKING
            else -> VoicePhase.IDLE
        }
}

enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * The conversation's state, typed or spoken. It turns cascade events into bubbles and owns the one
 * running request. With voice (ADR-043), a spoken turn's answer is read aloud sentence by sentence
 * as it streams.
 */
class ChatViewModel(
    private val session: ChatSession,
    private val device: () -> DeviceState,
    private val approvals: ApprovalCenter = ApprovalCenter(),
    private val speechIn: SpeechInput? = null,
    private val speechOut: SpeechOutput? = null,
) : ViewModel() {

    private val mutable = MutableStateFlow(ChatUiState(voiceAvailable = speechIn?.available == true))
    val state: StateFlow<ChatUiState> = mutable.asStateFlow()

    private var running: Job? = null
    private var listening: Job? = null
    private var nextId = 0L
    private val loaded = CompletableDeferred<Unit>()

    /** Whether the running turn is read aloud, and how much of its answer has been. */
    private var speakTurn = false
    private var spokenUpTo = 0

    init {
        speechOut?.let { out -> viewModelScope.launch { out.speaking.collect { speaking -> mutable.update { it.copy(speaking = speaking) } } } }
        viewModelScope.launch { approvals.requests.collect { pending -> mutable.update { it.copy(approval = pending.firstOrNull()) } } }
        viewModelScope.launch { session.context.collect { view -> mutable.update { it.copy(trimmed = view.trimmed) } } }
        // Restore the saved conversation (§14). Sending waits until it has loaded.
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val restored = session.history().mapNotNull { message ->
                    when (message) {
                        is ChatMessage.User -> Bubble(nextId++, fromUser = true, text = message.text)
                        is ChatMessage.Model -> message.text.takeIf(String::isNotEmpty)?.let { Bubble(nextId++, fromUser = false, text = it) }
                        is ChatMessage.Tool -> null
                    }
                }
                mutable.update { it.copy(bubbles = restored + it.bubbles) }
            } finally {
                mutable.update { it.copy(busy = false) }
                loaded.complete(Unit)
            }
        }
    }

    /** Sends [text]. A [spoken] turn's answer is read aloud, unless muted. */
    fun send(text: String, spoken: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || mutable.value.busy) return
        speakTurn = spoken && !mutable.value.muted && speechOut != null
        spokenUpTo = 0
        add(Bubble(nextId++, fromUser = true, text = trimmed))
        run(trimmed)
    }

    /** Starts listening once the saved conversation has loaded, for the assistant overlay (ADR-044). */
    fun listenWhenReady() {
        viewModelScope.launch {
            loaded.await()
            if (!mutable.value.listening) toggleListening()
        }
    }

    /**
     * Shows turns made on another screen, such as the assistant overlay, which shares this conversation.
     * Only while nothing is running here.
     */
    fun reloadIfIdle() {
        if (!loaded.isCompleted || mutable.value.busy || mutable.value.listening) return
        viewModelScope.launch {
            val saved = session.history().mapNotNull { message ->
                when (message) {
                    is ChatMessage.User -> message.text to true
                    is ChatMessage.Model -> message.text.takeIf(String::isNotEmpty)?.let { it to false }
                    is ChatMessage.Tool -> null
                }
            }
            val shown = mutable.value.bubbles.filterNot { it.failed && it.tier == null }.map { it.text to it.fromUser }
            if (saved == shown || mutable.value.busy) return@launch
            mutable.update { state -> state.copy(bubbles = saved.map { (text, fromUser) -> Bubble(nextId++, fromUser = fromUser, text = text) }) }
        }
    }

    /** Sends [text] once the saved conversation has loaded, for example what followed "Hey Anu". */
    fun sendWhenReady(text: String, spoken: Boolean = true) {
        viewModelScope.launch {
            loaded.await()
            send(text, spoken)
        }
    }

    /**
     * The orb: starts listening, or stops listening, or stops Anu talking. While a turn runs, a tap only
     * stops the speech, because one request runs at a time.
     */
    fun toggleListening() {
        val input = speechIn ?: return
        if (mutable.value.listening) {
            listening?.cancel()
            return
        }
        speechOut?.stop()
        if (mutable.value.busy) return
        if (!input.available) {
            mutable.update { it.copy(voiceAvailable = false, voiceNote = "This phone has no on-device speech recognition.") }
            return
        }
        listening = viewModelScope.launch {
            mutable.update { it.copy(listening = true, heard = "", level = 0f, voiceNote = null, offerLanguagePack = false, voiceAvailable = true) }
            try {
                input.listen().collect { event ->
                    when (event) {
                        SpeechEvent.Listening -> Unit
                        is SpeechEvent.Level -> mutable.update { it.copy(level = event.value) }
                        is SpeechEvent.Partial -> mutable.update { it.copy(heard = event.text) }
                        is SpeechEvent.Final -> {
                            mutable.update { it.copy(heard = event.text, listening = false, level = 0f) }
                            send(event.text, spoken = true)
                        }
                        is SpeechEvent.Error -> mutable.update { it.copy(voiceNote = event.reason, offerLanguagePack = event.needsLanguagePack) }
                    }
                }
            } finally {
                mutable.update { it.copy(listening = false, level = 0f) }
            }
        }
    }

    /** The user chose to get the on-device speech model for their language. */
    fun downloadLanguagePack() {
        speechIn?.downloadLanguagePack()
        mutable.update { it.copy(offerLanguagePack = false, voiceNote = "Getting on-device speech. Try again in a minute or two.") }
    }

    /** Stops or allows reading answers aloud. */
    fun toggleMute() {
        val muted = !mutable.value.muted
        if (muted) {
            speakTurn = false
            speechOut?.stop()
        }
        mutable.update { it.copy(muted = muted) }
    }

    /** "Retry with Pro": replaces the last answer with one from the Pro tier (§6). */
    fun retryWithPro() {
        if (mutable.value.busy) return
        viewModelScope.launch {
            val text = session.takeBackLast() ?: return@launch
            mutable.update { it.copy(bubbles = it.bubbles.dropLastWhile { bubble -> !bubble.fromUser }) }
            run(text, pinned = Tier.PRO)
        }
    }

    fun approveCloud() {
        val text = mutable.value.consentFor ?: return
        mutable.update { it.copy(consentFor = null) }
        run(text, cloudConsented = true)
    }

    fun declineCloud() {
        mutable.update { it.copy(consentFor = null) }
        add(Bubble(nextId++, fromUser = false, text = "Kept on this phone. Nothing was sent.", failed = true))
    }

    fun approve(id: String) = approvals.decide(id, ApprovalDecision.Approved)

    fun deny(id: String) = approvals.decide(id, ApprovalDecision.Denied("they tapped Don't do it"))

    fun stop() {
        running?.cancel()
    }

    private fun run(text: String, pinned: Tier? = null, cloudConsented: Boolean = false) {
        val id = nextId++
        add(Bubble(id, fromUser = false, text = "", streaming = true))
        mutable.update { it.copy(busy = true) }
        running = viewModelScope.launch {
            try {
                session.send(text, device(), pinned, cloudConsented).collect { reduce(id, text, it) }
            } catch (e: CancellationException) {
                edit(id) { it.copy(note = "Stopped") }
                throw e
            } catch (e: Exception) {
                edit(id) { it.copy(text = "Something went wrong: ${e.message}", failed = true) }
            } finally {
                edit(id) { it.copy(streaming = false) }
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun reduce(id: Long, text: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.Routed -> edit(id) { it.copy(tier = event.decision.tier) }
            is AgentEvent.TextDelta -> {
                edit(id) { it.copy(text = it.text + event.text) }
                speakProgress(id, final = false)
            }
            is AgentEvent.Discarded -> {
                edit(id) { it.copy(text = "", note = "That answer didn't pass its checks. Trying again…") }
                if (speakTurn) speechOut?.stop()
                spokenUpTo = 0
            }
            is AgentEvent.Escalated -> edit(id) { it.copy(tier = event.to, note = "Trying ${event.to.label}…") }
            is AgentEvent.ConsentNeeded -> mutable.update { state ->
                state.copy(bubbles = state.bubbles.filterNot { it.id == id }, consentFor = text)
            }
            is AgentEvent.ToolStarted -> {
                edit(id) { it.copy(text = "", note = activity(event.call.name)) }
                spokenUpTo = 0
            }
            is AgentEvent.ToolFinished -> {
                actionLine(event.outcome)?.let { line -> edit(id) { it.copy(actions = it.actions + line) } }
                linkFrom(event.outcome)?.let { link -> edit(id) { it.copy(links = it.links + link) } }
            }
            // Only the final answer is kept. Text the model wrote before calling tools is scaffolding.
            is AgentEvent.Completed -> {
                edit(id) {
                    it.copy(text = event.text, tier = event.tier, note = null, actions = event.actions.map(::actionLine), failed = event.unverifiedClaim)
                }
                speakProgress(id, final = true)
            }
            is AgentEvent.Failed -> {
                edit(id) { it.copy(text = friendly(event.reasons), failed = true, note = null) }
                spokenUpTo = 0
                speakProgress(id, final = true)
            }
        }
    }

    /** Reads the answer's newly complete sentences aloud, on a spoken turn. */
    private fun speakProgress(id: Long, final: Boolean) {
        if (!speakTurn) return
        val out = speechOut ?: return
        val text = mutable.value.bubbles.firstOrNull { it.id == id }?.text ?: return
        val (sentences, next) = Sentences.next(text, spokenUpTo, final)
        spokenUpTo = next
        sentences.forEach(out::speak)
    }

    private fun actionLine(outcome: ToolOutcome): String? = when (outcome) {
        is ToolOutcome.Ok -> "✓ ${outcome.summary}"
        is ToolOutcome.Rejected -> "✗ Not done: ${outcome.summary}"
        is ToolOutcome.Failed -> "! Failed: ${outcome.summary}"
        is ToolOutcome.Invalid -> null
    }

    /** A link a tool's result offers as `open_url` and `open_label`. Only web links, never other schemes. */
    private fun linkFrom(outcome: ToolOutcome): LinkAction? {
        val result = (outcome as? ToolOutcome.Ok)?.result as? JsonObject ?: return null
        val url = (result["open_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val label = (result["open_label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "Open link"
        return LinkAction(label.take(60), url)
    }

    private fun actionLine(action: ActionRecord): String = when (action.status) {
        ActionRecord.Status.DONE -> "✓ ${action.summary}"
        ActionRecord.Status.NOT_DONE -> "✗ Not done: ${action.summary}"
        ActionRecord.Status.FAILED -> "! Failed: ${action.summary}"
    }

    /** What the user sees while a tool runs. */
    private fun activity(tool: String): String = when {
        tool.startsWith("calendar_create") -> "Preparing the event…"
        tool.startsWith("calendar_") -> "Checking your calendar…"
        tool.startsWith("contacts_") -> "Looking up contacts…"
        tool == "messages_reply" -> "Preparing the reply…"
        tool.startsWith("messages_") || tool.startsWith("sms_") -> "Checking your messages…"
        tool == "email_send" || tool == "email_create_draft" -> "Preparing the email…"
        tool == "memory_remember" -> "Remembering…"
        tool == "memory_forget" -> "Finding what to forget…"
        tool == "memory_search" -> "Checking what I remember…"
        tool.startsWith("email_") -> "Checking your email…"
        tool == "shop_search" -> "Searching your stores…"
        tool == "shop_checkout" -> "Preparing the checkout…"
        tool.startsWith("mcp_") -> "Asking your service…"
        else -> "Working…"
    }

    private fun add(bubble: Bubble) = mutable.update { it.copy(bubbles = it.bubbles + bubble) }

    private fun edit(id: Long, change: (Bubble) -> Bubble) =
        mutable.update { state -> state.copy(bubbles = state.bubbles.map { if (it.id == id) change(it) else it }) }

    private fun friendly(reasons: List<String>): String = when {
        reasons.any { it == "no model tier is available" } ->
            "No model is ready yet. Download one in Settings, or add a Gemini key there."
        else -> "I couldn't answer that: ${reasons.lastOrNull() ?: "unknown error"}"
    }
}

/** How the UI names each tier, so users always know where an answer came from (§6). */
val Tier.label: String
    get() = when (this) {
        Tier.BASE -> "On-device · Base"
        Tier.PRO -> "On-device · Pro"
        Tier.CLOUD -> "Cloud · Gemini"
    }
