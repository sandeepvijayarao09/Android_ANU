package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
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

/** The brain wired to a tier, or null when that tier has none (for example no cloud key yet). */
fun interface BrainProvider {
    fun brainFor(tier: Tier): Brain?
}

/** Checks a finished answer (P6). Returns null when it is acceptable, otherwise the reason it isn't. */
fun interface OutputValidator {
    fun problem(text: String, toolCalls: List<ToolCall>): String?

    companion object {
        /** Plain chat: any non-blank text, or at least one tool call. */
        val NonBlank = OutputValidator { text, calls -> if (text.isBlank() && calls.isEmpty()) "empty answer" else null }
    }
}

sealed interface CascadeEvent {
    data class Routed(val decision: RouteDecision) : CascadeEvent

    data class TextDelta(val tier: Tier, val text: String) : CascadeEvent

    /** The text streamed by this attempt is being thrown away. The UI must clear it. */
    data class Discarded(val tier: Tier, val reason: String) : CascadeEvent

    data class Escalated(val from: Tier, val to: Tier, val reason: String) : CascadeEvent

    /** Nothing was sent. Ask the user, then run again with `cloudConsented = true` (§12.2). */
    data class ConsentNeeded(val decision: RouteDecision) : CascadeEvent

    data class Completed(
        val tier: Tier,
        val text: String,
        val toolCalls: List<ToolCall>,
        val usage: BrainEvent.Usage?,
    ) : CascadeEvent

    data class Failed(val reasons: List<String>) : CascadeEvent
}

/**
 * Stage C of the router (ARCHITECTURE §6), following P6.
 * - It runs a request on the routed tier.
 * - When the answer fails validation, it repairs once on the same tier with greedy sampling.
 * - It escalates one tier after a failed repair, or straight away after an error.
 * - Cloud tiers that need consent stop the cascade before anything is sent.
 */
class Cascade(private val router: Router, private val brains: BrainProvider) {

    fun run(
        route: RouteRequest,
        device: DeviceState,
        request: BrainRequest,
        validator: OutputValidator = OutputValidator.NonBlank,
    ): Flow<CascadeEvent> = flow {
        // The route is the source of truth for what the context contains (ledger, I3).
        val request = request.copy(dataClasses = route.dataClasses, purpose = route.skill.name.lowercase())
        var decision = when (val result = router.route(route, device)) {
            is RouteResult.Unavailable -> return@flow emit(CascadeEvent.Failed(result.reasons))
            is RouteResult.Routed -> result.decision
        }
        while (true) {
            if (decision.consent == Consent.ASK) return@flow emit(CascadeEvent.ConsentNeeded(decision))
            emit(CascadeEvent.Routed(decision))

            // A guarded phone shortens answers (ADR-072): writing takes the smaller of its own limit and the guard's.
            val limited = decision.maxOutputTokens?.let { guard -> request.copy(maxOutputTokens = minOf(request.maxOutputTokens ?: guard, guard)) } ?: request
            val problem = when (val brain = brains.brainFor(decision.tier)) {
                null -> "no brain is wired for ${decision.tier}"
                else -> attempt(brain, decision.tier, limited, validator)?.let { first ->
                    if (first.retryable) {
                        attempt(brain, decision.tier, limited.copy(sampling = Sampling.Precise), validator)?.reason
                    } else {
                        first.reason
                    }
                }
            } ?: return@flow

            decision = router.escalate(route, decision, problem)
                ?.also { emit(CascadeEvent.Escalated(decision.tier, it.tier, problem)) }
                ?: return@flow emit(CascadeEvent.Failed(decision.reasons + "${decision.tier} failed: $problem"))
        }
    }

    private class Problem(val reason: String, val retryable: Boolean)

    /** Streams one attempt. Returns null after emitting [CascadeEvent.Completed], otherwise the problem. */
    private suspend fun FlowCollector<CascadeEvent>.attempt(
        brain: Brain,
        tier: Tier,
        request: BrainRequest,
        validator: OutputValidator,
    ): Problem? {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var usage: BrainEvent.Usage? = null
        try {
            brain.stream(request).collect { event ->
                when (event) {
                    is BrainEvent.TextDelta -> {
                        text.append(event.text)
                        emit(CascadeEvent.TextDelta(tier, event.text))
                    }
                    is BrainEvent.ToolCallRequested -> calls += event.call
                    is BrainEvent.Usage -> usage = event
                    is BrainEvent.Finished -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e // never swallow cancellation (§18)
        } catch (e: Exception) {
            val reason = "error: ${e.message ?: e::class.simpleName}"
            emit(CascadeEvent.Discarded(tier, reason))
            return Problem(reason, retryable = false)
        }
        val invalid = validator.problem(text.toString(), calls)
        if (invalid != null) {
            emit(CascadeEvent.Discarded(tier, invalid))
            return Problem(invalid, retryable = true)
        }
        emit(CascadeEvent.Completed(tier, text.toString(), calls.toList(), usage))
        return null
    }
}
