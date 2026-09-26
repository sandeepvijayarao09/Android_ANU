package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.router.Consent
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteDecision
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.RouteResult
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** Limits for one agent run (§7.2). */
data class Budgets(val maxSteps: Int = 8, val maxToolCalls: Int = 12)

/** What a tool call actually did, as the broker recorded it. The chat shows these under each answer (ADR-027). */
data class ActionRecord(val tool: String, val summary: String, val status: Status, val wrote: Boolean, val reason: String? = null) {
    enum class Status { DONE, NOT_DONE, FAILED }
}

sealed interface AgentEvent {
    data class Routed(val decision: RouteDecision) : AgentEvent

    data class TextDelta(val tier: Tier, val text: String) : AgentEvent

    /** Text streamed by this step is being thrown away. */
    data class Discarded(val tier: Tier, val reason: String) : AgentEvent

    data class Escalated(val from: Tier, val to: Tier, val reason: String) : AgentEvent

    /** Nothing more was sent. Ask the user, then run again with `cloudConsented = true` (§12.2). */
    data class ConsentNeeded(val decision: RouteDecision) : AgentEvent

    data class ToolStarted(val call: ToolCall) : AgentEvent

    data class ToolFinished(val call: ToolCall, val outcome: ToolOutcome) : AgentEvent

    /**
     * [added] holds every message this run produced, including tool turns, in transcript order.
     * [actions] is what really happened. [unverifiedClaim] is true when the answer still claims an action
     * that didn't happen, after repair and escalation. The text then carries a warning.
     */
    data class Completed(
        val tier: Tier,
        val text: String,
        val added: List<ChatMessage>,
        val actions: List<ActionRecord> = emptyList(),
        val unverifiedClaim: Boolean = false,
    ) : AgentEvent

    data class Failed(val reasons: List<String>) : AgentEvent
}

/**
 * The streaming tool loop (ARCHITECTURE §7.2, ADR-004). Each step:
 * 1. asks the routed brain;
 * 2. hands any tool calls to the [ToolBroker];
 * 3. appends the calls and their results to the transcript;
 * 4. repeats until the brain answers in plain text.
 *
 * Escalation continues the *same* transcript on the next tier, so work already done, including side
 * effects, is never repeated (ADR-026). An empty answer is repaired once with greedy sampling (P6).
 */
class Agent(
    private val router: Router,
    private val brains: BrainProvider,
    private val broker: ToolBroker,
    private val budgets: Budgets = Budgets(),
) {

    fun run(
        route: RouteRequest,
        device: DeviceState,
        system: String,
        history: List<ChatMessage>,
        tools: List<String>,
        turnKey: String,
        runId: String = turnKey,
        /** When the user sent the newest message. Brains show it with that message (ADR-018). */
        sentAt: String? = null,
    ): Flow<AgentEvent> = flow {
        var route = route
        var decision = when (val result = router.route(route, device)) {
            is RouteResult.Unavailable -> return@flow emit(AgentEvent.Failed(result.reasons))
            is RouteResult.Routed -> result.decision
        }
        val specs = broker.specs(tools)
        val state = RunState(runId, turnKey, BrainLocation.ON_DEVICE, route.cloudConsented)
        val added = mutableListOf<ChatMessage>()
        var steps = 0
        var toolCalls = 0
        var invalidStreak = 0
        var repaired = false
        var announced: Tier? = null
        val actions = mutableListOf<ActionRecord>()
        var claimRepaired = false
        var systemNote: String? = null

        suspend fun FlowCollector<AgentEvent>.escalate(why: String): Boolean {
            val next = router.escalate(route, decision, why)
            if (next == null) {
                emit(AgentEvent.Failed(decision.reasons + "${decision.tier} failed: $why"))
                return false
            }
            emit(AgentEvent.Escalated(decision.tier, next.tier, why))
            decision = next
            repaired = false
            return true
        }

        while (true) {
            if (decision.consent == Consent.ASK) return@flow emit(AgentEvent.ConsentNeeded(decision))
            if (announced != decision.tier) {
                emit(AgentEvent.Routed(decision))
                announced = decision.tier
            }
            val brain = brains.brainFor(decision.tier)
            if (brain == null) {
                if (!escalate("no brain is wired for ${decision.tier}")) return@flow
                continue
            }
            if (++steps > budgets.maxSteps) return@flow emit(AgentEvent.Failed(listOf("stopped after ${budgets.maxSteps} steps")))
            state.brainLocation = brain.capabilities.location

            val request = BrainRequest(
                system = systemNote?.let { "$system\n\n$it" } ?: system,
                transcript = history + added,
                tools = specs,
                dataClasses = route.dataClasses,
                purpose = route.skill.name.lowercase(),
                sampling = if (repaired) Sampling.Precise else Sampling.Default,
                sentAt = sentAt,
            )
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            try {
                brain.stream(request).collect { event ->
                    when (event) {
                        is BrainEvent.TextDelta -> {
                            text.append(event.text)
                            emit(AgentEvent.TextDelta(decision.tier, event.text))
                        }
                        is BrainEvent.ToolCallRequested -> calls += event.call
                        is BrainEvent.Usage, is BrainEvent.Finished -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e // never swallow cancellation (§18)
            } catch (e: Exception) {
                val why = "error: ${e.message ?: e::class.simpleName}"
                emit(AgentEvent.Discarded(decision.tier, why))
                if (!escalate(why)) return@flow
                continue
            }

            if (calls.isEmpty()) {
                if (text.isBlank()) {
                    emit(AgentEvent.Discarded(decision.tier, "empty answer"))
                    if (!repaired) {
                        repaired = true
                    } else if (!escalate("empty answer")) {
                        return@flow
                    }
                    continue
                }
                if (ActionClaims.hasControlMarkup(text.toString())) {
                    // A tool call the runtime didn't parse. Repair once, then escalate (P6).
                    emit(AgentEvent.Discarded(decision.tier, "raw tool-call markup in the answer"))
                    if (!repaired) {
                        repaired = true
                    } else if (!escalate("raw tool-call markup in the answer")) {
                        return@flow
                    }
                    continue
                }
                // An answer may only claim what the broker recorded (ADR-027).
                val wroteSomething = actions.any { it.wrote && it.status == ActionRecord.Status.DONE }
                if (!wroteSomething && ActionClaims.claimsAction(text.toString())) {
                    val why = "claimed an action that didn't happen"
                    emit(AgentEvent.Discarded(decision.tier, why))
                    if (!claimRepaired) {
                        claimRepaired = true
                        repaired = true
                        systemNote = ActionClaims.NOTHING_DONE_NOTE
                        continue
                    }
                    val next = router.escalate(route, decision, why)
                    if (next != null) {
                        emit(AgentEvent.Escalated(decision.tier, next.tier, why))
                        decision = next
                        continue
                    }
                    val warned = "$text\n\n${ActionClaims.NOT_DONE_WARNING}"
                    added += ChatMessage.Model(warned)
                    return@flow emit(AgentEvent.Completed(decision.tier, warned, added.toList(), actions.toList(), unverifiedClaim = true))
                }
                added += ChatMessage.Model(text.toString())
                return@flow emit(AgentEvent.Completed(decision.tier, text.toString(), added.toList(), actions.toList()))
            }

            added += ChatMessage.Model(text.toString(), calls.toList())
            val results = mutableListOf<ToolResult>()
            var anyValid = false
            for (call in calls) {
                if (++toolCalls > budgets.maxToolCalls) {
                    return@flow emit(AgentEvent.Failed(listOf("stopped after ${budgets.maxToolCalls} tool calls")))
                }
                emit(AgentEvent.ToolStarted(call))
                val outcome = broker.execute(call, state)
                emit(AgentEvent.ToolFinished(call, outcome))
                when (outcome) {
                    is ToolOutcome.Ok -> actions += ActionRecord(call.name, outcome.summary, ActionRecord.Status.DONE, outcome.wrote)
                    is ToolOutcome.Rejected -> actions += ActionRecord(call.name, outcome.summary, ActionRecord.Status.NOT_DONE, false, outcome.reason)
                    is ToolOutcome.Failed -> actions += ActionRecord(call.name, outcome.summary, ActionRecord.Status.FAILED, false, outcome.reason)
                    is ToolOutcome.Invalid -> Unit // never ran, so it isn't an action
                }
                if (outcome !is ToolOutcome.Invalid) anyValid = true
                results += ToolResult(call.name, outcome.toResultJson())
            }
            added += ChatMessage.Tool(results)
            // What tools returned counts too, so an escalation to the cloud re-checks consent with it.
            route = route.copy(dataClasses = route.dataClasses + state.dataClassesSeen)
            invalidStreak = if (anyValid) 0 else invalidStreak + 1
            if (invalidStreak >= 2) {
                if (!escalate("repeated invalid tool calls")) return@flow
                invalidStreak = 0
            }
        }
    }
}
