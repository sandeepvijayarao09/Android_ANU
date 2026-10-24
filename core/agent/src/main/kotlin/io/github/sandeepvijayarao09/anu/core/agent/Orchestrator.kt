package io.github.sandeepvijayarao09.anu.core.agent

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
)
