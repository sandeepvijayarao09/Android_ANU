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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

data class ChatUiState(
    val bubbles: List<Bubble> = emptyList(),
    val busy: Boolean = false,
    /** Set when a message needs cloud consent (§12.2). Holds the text waiting to be sent. */
    val consentFor: String? = null,
    /** The oldest action waiting for the user's approval (I2). */
    val approval: ApprovalRequest? = null,
    /** True once the chat is longer than the model's view, so older messages are left out (ADR-040). */
    val trimmed: Boolean = false,
)

/** The chat screen's state. It turns cascade events into bubbles and owns the one running request. */
class ChatViewModel(
    private val session: ChatSession,
    private val device: () -> DeviceState,
    private val approvals: ApprovalCenter = ApprovalCenter(),
) : ViewModel() {

    private val mutable = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutable.asStateFlow()

    private var running: Job? = null
    private var nextId = 0L

    init {
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
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || mutable.value.busy) return
        add(Bubble(nextId++, fromUser = true, text = trimmed))
        run(trimmed)
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
            is AgentEvent.TextDelta -> edit(id) { it.copy(text = it.text + event.text) }
            is AgentEvent.Discarded -> edit(id) { it.copy(text = "", note = "That answer didn't pass its checks. Trying again…") }
            is AgentEvent.Escalated -> edit(id) { it.copy(tier = event.to, note = "Trying ${event.to.label}…") }
            is AgentEvent.ConsentNeeded -> mutable.update { state ->
                state.copy(bubbles = state.bubbles.filterNot { it.id == id }, consentFor = text)
            }
            is AgentEvent.ToolStarted -> edit(id) { it.copy(text = "", note = activity(event.call.name)) }
            is AgentEvent.ToolFinished -> actionLine(event.outcome)?.let { line -> edit(id) { it.copy(actions = it.actions + line) } }
            // Only the final answer is kept. Text the model wrote before calling tools is scaffolding.
            is AgentEvent.Completed -> edit(id) {
                it.copy(text = event.text, tier = event.tier, note = null, actions = event.actions.map(::actionLine), failed = event.unverifiedClaim)
            }
            is AgentEvent.Failed -> edit(id) { it.copy(text = friendly(event.reasons), failed = true, note = null) }
        }
    }

    private fun actionLine(outcome: ToolOutcome): String? = when (outcome) {
        is ToolOutcome.Ok -> "✓ ${outcome.summary}"
        is ToolOutcome.Rejected -> "✗ Not done: ${outcome.summary}"
        is ToolOutcome.Failed -> "! Failed: ${outcome.summary}"
        is ToolOutcome.Invalid -> null
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
