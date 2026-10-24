package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.router.Skill
import kotlinx.coroutines.flow.Flow

/**
 * A chat turn handed to sub-agents or a workflow (ADR-104): Google's ADK runs them, and every tool they call goes
 * through the broker in [run], with its taint, approvals and audit, as [Agent] does. `core:agent` knows only this
 * interface; `agent:adk` implements it.
 */
interface Orchestrator {
    /** The skills it takes, such as a daily brief or a request across several areas. */
    val skills: Set<Skill>

    /**
     * Runs [turn], emitting the events [Agent.run] does, so the chat saves, shows and speaks it the same way: tools
     * started and finished, then one [AgentEvent.Completed] with the answer, or [AgentEvent.Failed].
     */
    fun run(turn: OrchestratedTurn): Flow<AgentEvent>
}

/** What a turn hands the orchestrator: the [skill] it was routed to, the user's [text], and the run's broker state. */
data class OrchestratedTurn(
    val skill: Skill,
    val text: String,
    val turnKey: String,
    /** When the user sent it, with the dates of the days it names (ADR-085). */
    val sentAt: String?,
    /** Content from others the chat already shows, which taints the run (P4). */
    val inView: Set<UntrustedContent>,
    /** The last exchanges the chat shows, so "tomorrow instead" or "that one" mean something ([recentChat]). */
    val recent: String? = null,
    /** Facts from memory that bear on the message (ADR-054), as the chat's own turn gets them. */
    val facts: String? = null,
)

/**
 * The last [max] user and Anu messages of [messages] with words, oldest first, each cut to [chars]: "User: …" and
 * "Anu: …". Null when there are none. Tool calls and results are left out: the receipts in Anu's words say what ran.
 */
fun recentChat(messages: List<ChatMessage>, max: Int = 6, chars: Int = 300): String? = messages
    .mapNotNull { message ->
        when (message) {
            is ChatMessage.User -> message.text.takeIf { it.isNotBlank() }?.let { "User: $it" }
            is ChatMessage.Model -> message.text.takeIf { it.isNotBlank() }?.let { "Anu: $it" }
            is ChatMessage.Tool -> null
        }
    }
    .takeLast(max)
    .map { if (it.length > chars) it.take(chars).trimEnd() + "…" else it }
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n")
