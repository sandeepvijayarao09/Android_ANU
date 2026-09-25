package io.github.sandeepvijayarao09.anu.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.agent.CascadeEvent
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
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
)

data class ChatUiState(
    val bubbles: List<Bubble> = emptyList(),
    val busy: Boolean = false,
    /** Set when a message needs cloud consent (§12.2). Holds the text waiting to be sent. */
    val consentFor: String? = null,
)

/** The chat screen's state. It turns cascade events into bubbles and owns the one running request. */
class ChatViewModel(private val session: ChatSession, private val device: () -> DeviceState) : ViewModel() {

    private val mutable = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutable.asStateFlow()

    private var running: Job? = null
    private var nextId = 0L

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

    private fun reduce(id: Long, text: String, event: CascadeEvent) {
        when (event) {
            is CascadeEvent.Routed -> edit(id) { it.copy(tier = event.decision.tier) }
            is CascadeEvent.TextDelta -> edit(id) { it.copy(text = it.text + event.text) }
            is CascadeEvent.Discarded -> edit(id) { it.copy(text = "", note = "That answer didn't pass its checks. Trying again…") }
            is CascadeEvent.Escalated -> edit(id) { it.copy(tier = event.to, note = "Trying ${event.to.label}…") }
            is CascadeEvent.ConsentNeeded -> mutable.update { state ->
                state.copy(bubbles = state.bubbles.filterNot { it.id == id }, consentFor = text)
            }
            is CascadeEvent.Completed -> edit(id) { it.copy(text = event.text, tier = event.tier, note = null) }
            is CascadeEvent.Failed -> edit(id) { it.copy(text = friendly(event.reasons), failed = true, note = null) }
        }
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
