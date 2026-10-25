package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Document
import io.github.sandeepvijayarao09.anu.core.model.echoesFraming
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.VoiceNote
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.model.withoutFraming
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How an escalation past a tier with no model installed is worded. That tier never tried. */
internal const val NO_BRAIN = "no brain is wired"

/** Why a turn went to Pro on Base's own opinion (ADR-106). */
internal const val BEYOND_BASE = "Base judged it needs Pro"

/** Base's opinion, or none if asking failed: the rules' choice then stands. */
private suspend fun opinion(baseOpinion: BaseOpinion, text: String): Boolean? = try {
    baseOpinion.beyondBase(text)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** What a cloud brain reads in place of a tool result that can hold sensitive data (ADR-087). */
internal val KEPT_ON_PHONE = ToolOutcome.Rejected("This result can hold sensitive data, which stays on this phone.").toResultJson()

/** Limits for one agent run (§7.2). */
data class Budgets(val maxSteps: Int = 8, val maxToolCalls: Int = 12)

/**
 * What a tool call actually did, as the broker recorded it. The chat shows these under each answer (ADR-027).
 * [destination] is the third party its input went to, such as a store's host, when the tool says.
 */
data class ActionRecord(
    val tool: String,
    val summary: String,
    val status: Status,
    val wrote: Boolean,
    val reason: String? = null,
    val destination: String? = null,
) {
    enum class Status { DONE, NOT_DONE, FAILED }
}

sealed interface AgentEvent {
    data class Routed(val decision: RouteDecision) : AgentEvent

    data class TextDelta(val tier: Tier, val text: String) : AgentEvent

    /** The model's reasoning before its answer (G4, ADR-077). It is shown apart, and never becomes the answer. */
    data class Thinking(val tier: Tier, val text: String) : AgentEvent

    /** Text streamed by this step is being thrown away. */
    data class Discarded(val tier: Tier, val reason: String) : AgentEvent

    data class Escalated(val from: Tier, val to: Tier, val reason: String) : AgentEvent

    /**
     * The turn needs the user's consent to use the cloud (§12.2), and nothing more was sent: ask the user, then run
     * again with `cloudConsented = true`. [facts] are the facts from memory the cloud would also see, if the user
     * shares them (ADR-054): relevant ones only, never sensitive.
     */
    data class ConsentNeeded(val decision: RouteDecision, val facts: List<String> = emptyList()) : AgentEvent

    data class ToolStarted(val call: ToolCall) : AgentEvent

    data class ToolFinished(val call: ToolCall, val outcome: ToolOutcome) : AgentEvent

    /**
     * [added] holds every message this run produced, including tool turns, in transcript order.
     * [actions] is what really happened. [unverifiedClaim] is true when the answer still claims an action, or
     * a save to memory, that didn't happen, after repair and escalation. The text then carries a warning.
     * [tokens] compares the estimate of the final request and answer with the runtime's count (ADR-041).
     * [usage] is how the final step ran. [details] is what "About this answer" shows, added by [ChatSession]
     * (ADR-071).
     */
    data class Completed(
        val tier: Tier,
        val text: String,
        val added: List<ChatMessage>,
        val actions: List<ActionRecord> = emptyList(),
        val unverifiedClaim: Boolean = false,
        val tokens: TokenSample? = null,
        val usage: BrainEvent.Usage? = null,
        val details: AnswerDetails? = null,
        /** The route the final answer took, including any guard that shaped it (ADR-072). */
        val decision: RouteDecision? = null,
        /** A phone command done with no model (G6, ADR-076): [tier] means nothing then. */
        val direct: Boolean = false,
        /** The ADK agents that worked on an orchestrated turn (ADR-110). */
        val agents: List<AgentStep> = emptyList(),
    ) : AgentEvent

    /** A voice note being written down on the phone before the turn goes on (G3, ADR-078): its [words] so far. */
    data class Transcribing(val words: String) : AgentEvent

    /** The voice note is written down: the turn goes on with [note], which a retry can send again as it is. */
    data class Transcribed(val note: VoiceNote) : AgentEvent

    /** A PDF being read in pieces on the phone before the turn goes on (ADR-095): up to page [page] of the [of] read. */
    data class ReadingDocument(val name: String, val page: Int, val of: Int) : AgentEvent

    /** The PDF is read: the turn goes on with [document], which a retry can send again as it is. */
    data class DocumentRead(val document: Document) : AgentEvent

    data class Failed(val reasons: List<String>) : AgentEvent

    /**
     * The turn ended with nothing to answer, which isn't a failure: [note] says why, as for a recording with no words
     * (ADR-090). It's shown as a note, and nothing is saved.
     */
    data class NothingToAnswer(val note: String) : AgentEvent
}

/** [reason] as the end of a sentence: tools give their reasons without a full stop ("this phone has no flashlight"). */
private fun sentence(reason: String): String = reason.trimEnd().let { if (it.endsWith('.') || it.endsWith('!') || it.endsWith('?')) it else "$it." }

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
    /** Base's own opinion of a request the rules gave it, beside Pro on the GPU (ADR-106). */
    private val baseOpinion: BaseOpinion? = null,
) {

    /** The final request and answer as estimated, against what the runtime counted after them (ADR-041). */
    private fun sample(request: BrainRequest, brain: Brain, answer: CharSequence, usage: BrainEvent.Usage?): TokenSample? =
        usage?.let {
            TokenSample(
                estimated = Tokens.ofRequest(request) + Tokens.of(ChatMessage.Model(answer.toString())),
                counted = it.contextTokens,
                onDevice = brain.capabilities.location == BrainLocation.ON_DEVICE,
            )
        }

    /** The definitions of [tools] as the model will see them, so their size can be budgeted (§7.3). */
    fun toolSpecs(tools: List<String>): List<ToolSpec> = broker.specs(tools)

    /** What kind of content from other people a result from [toolName] holds (P4). See [ToolBroker.untrustedContent]. */
    fun untrustedContent(toolName: String): UntrustedContent? = broker.untrustedContent(toolName)

    /**
     * [messages] as the cloud may see them: each result that can hold sensitive data, such as memory's facts, is
     * replaced with a note that it stays on the phone. Consent covers the message and the facts on the consent sheet,
     * never what a tool returned (ADR-087). On-device brains and the saved chat keep the real results.
     */
    private fun withoutSensitiveResults(messages: List<ChatMessage>): List<ChatMessage> = messages.map { message ->
        if (message !is ChatMessage.Tool) return@map message
        message.copy(results = message.results.map { if (broker.returnsSensitive(it.toolName)) it.copy(result = KEPT_ON_PHONE) else it })
    }

    /**
     * A phone command with no model (G6, ADR-076): [call] runs through the broker like a model's call, with the same
     * risk tier, approval, taint ([inView]) and audit, and the answer is the tool's own words for what happened.
     */
    fun runDirect(call: ToolCall, turnKey: String, inView: Set<UntrustedContent> = emptySet()): Flow<AgentEvent> = flow {
        val state = RunState(turnKey, turnKey, BrainLocation.ON_DEVICE, cloudConsented = false, inView = inView)
        emit(AgentEvent.ToolStarted(call))
        val outcome = broker.execute(call, state)
        emit(AgentEvent.ToolFinished(call, outcome))
        val action = when (outcome) {
            is ToolOutcome.Ok -> ActionRecord(call.name, outcome.summary, ActionRecord.Status.DONE, outcome.wrote, destination = outcome.destination)
            is ToolOutcome.Rejected -> ActionRecord(call.name, outcome.summary, ActionRecord.Status.NOT_DONE, false, outcome.reason, outcome.destination)
            is ToolOutcome.Failed -> ActionRecord(call.name, outcome.summary, ActionRecord.Status.FAILED, false, outcome.reason, outcome.destination)
            is ToolOutcome.Invalid -> null
        }
        val text = when (outcome) {
            is ToolOutcome.Ok -> ((outcome.result as? JsonObject)?.get("said") as? JsonPrimitive)?.content ?: outcome.summary
            is ToolOutcome.Rejected -> "Not done."
            is ToolOutcome.Failed -> "That didn't work: ${sentence(outcome.reason)}"
            is ToolOutcome.Invalid -> "That didn't work: ${sentence(outcome.reason)}"
        }
        // An app task picks its steps with an on-device model, and says which (ADR-098): the answer names it.
        val used = ((outcome as? ToolOutcome.Ok)?.result as? JsonObject)?.get("brain")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val tier = when (used) {
            BrainId.PRO.name -> Tier.PRO
            BrainId.BASE.name -> Tier.BASE
            else -> null
        }
        val untrusted = if (outcome is ToolOutcome.Ok || outcome is ToolOutcome.Failed) broker.untrustedContent(call.name) else null
        val added = listOf(
            ChatMessage.Model("", listOf(call)),
            ChatMessage.Tool(listOf(ToolResult(call.name, outcome.toResultJson(), untrusted))),
            ChatMessage.Model(text),
        )
        emit(AgentEvent.Completed(tier ?: Tier.BASE, text, added, listOfNotNull(action), direct = tier == null))
    }

    fun run(
        route: RouteRequest,
        device: DeviceState,
        system: String,
        history: List<ChatMessage>,
        tools: List<String>,
        turnKey: String,
        runId: String = turnKey,
        /** When the user sent the newest message. Brains show it with that message (ADR-033). */
        sentAt: String? = null,
        /** The coming week's dates, for a turn that can act. Brains show them with the newest message too (ADR-114). */
        dates: String? = null,
        /** What Anu remembers. Only on-device brains use it (ADR-038). */
        memory: String? = null,
        /** Facts from memory that bear on the newest message, shown with it by on-device brains (ADR-054). */
        relevant: String? = null,
        /** The facts the user chose to share with the cloud on the consent sheet (ADR-054). */
        sharedFacts: List<String> = emptyList(),
        /** The facts to show on the consent sheet, if the turn needs consent (ADR-054). */
        consentFacts: List<String> = emptyList(),
        /** Whether on-device models reason before answering (G4, ADR-077). */
        thinking: Boolean = false,
        /** Content from other people that [history] already shows, so the run starts tainted (P4, §7.2). */
        inView: Set<UntrustedContent> = emptySet(),
    ): Flow<AgentEvent> = flow {
        var route = route
        // The data classes of the chat's own words, before any tool runs. A cloud request never carries sensitive
        // results, so it doesn't count them for the Privacy Ledger (ADR-087).
        val asked = route.dataClasses
        var decision = when (val result = router.route(route, device)) {
            is RouteResult.Unavailable -> return@flow emit(AgentEvent.Failed(result.reasons))
            is RouteResult.Routed -> result.decision
        }
        // Beside Pro on the GPU, Base says whether a request the rules gave it is beyond it; if so, Pro answers (ADR-106).
        if (baseOpinion != null && decision.tier == Tier.BASE && device.baseOnTpu && device.proOnGpu && route.pinnedTier == null &&
            Tier.PRO in decision.allowed && opinion(baseOpinion, route.text) == true
        ) {
            router.escalate(route, decision, BEYOND_BASE)?.let { decision = it }
        }
        val specs = broker.specs(tools)
        val state = RunState(runId, turnKey, BrainLocation.ON_DEVICE, route.cloudConsented, inView)
        val added = mutableListOf<ChatMessage>()
        var steps = 0
        var toolCalls = 0
        var invalidStreak = 0
        var repaired = false
        var announced: Tier? = null
        val actions = mutableListOf<ActionRecord>()
        var claimRepaired = false
        // Every kind of claim the run has had to repair, so a stronger tier gets all their notes.
        val claimed = mutableSetOf<ActionClaims.Claim>()
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
            if (decision.consent == Consent.ASK) return@flow emit(AgentEvent.ConsentNeeded(decision, consentFacts))
            if (announced != decision.tier) {
                emit(AgentEvent.Routed(decision))
                announced = decision.tier
            }
            val brain = brains.brainFor(decision.tier)
            if (brain == null) {
                if (!escalate("$NO_BRAIN for ${decision.tier}")) return@flow
                continue
            }
            if (++steps > budgets.maxSteps) return@flow emit(AgentEvent.Failed(listOf("stopped after ${budgets.maxSteps} steps")))
            state.brainLocation = brain.capabilities.location

            val transcript = history + added
            val request = BrainRequest(
                system = systemNote?.let { "$system\n\n$it" } ?: system,
                // A cloud brain never reads a result that can hold sensitive data, even one from a step on the phone
                // before an escalation, or from an earlier turn still in view (ADR-087).
                transcript = if (state.brainLocation == BrainLocation.CLOUD) withoutSensitiveResults(transcript) else transcript,
                tools = specs,
                dataClasses = if (state.brainLocation == BrainLocation.CLOUD) asked + (state.dataClassesSeen - DataClass.SENSITIVE) else route.dataClasses,
                purpose = route.skill.name.lowercase(),
                // A turn that can act samples cooler (ADR-085). The tools already key the on-device conversation,
                // so this rebuilds nothing more.
                sampling = when {
                    repaired -> Sampling.Precise
                    specs.isNotEmpty() -> Sampling.Acting
                    else -> Sampling.Default
                },
                sentAt = sentAt,
                dates = dates,
                memory = memory,
                onDeviceNote = ON_DEVICE_NOTE,
                relevant = relevant,
                sharedFacts = sharedFacts,
                maxOutputTokens = decision.maxOutputTokens,
                thinking = thinking,
            )
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            var usage: BrainEvent.Usage? = null
            try {
                brain.stream(request).collect { event ->
                    when (event) {
                        is BrainEvent.TextDelta -> if (event.thought) {
                            emit(AgentEvent.Thinking(decision.tier, event.text))
                        } else {
                            text.append(event.text)
                            emit(AgentEvent.TextDelta(decision.tier, event.text))
                        }
                        is BrainEvent.ToolCallRequested -> calls += event.call
                        is BrainEvent.Usage -> usage = event
                        is BrainEvent.Finished -> Unit
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
                if (request.echoesFraming(text.toString())) {
                    // The model quoted the time or the facts it was given with the message (ADR-058). Write the
                    // answer again once; if it still quotes them, cut them rather than send the chat to the cloud.
                    if (!repaired) {
                        emit(AgentEvent.Discarded(decision.tier, "quoted the message's framing"))
                        repaired = true
                        continue
                    }
                    val cleaned = request.withoutFraming(text.toString())
                    text.setLength(0)
                    text.append(cleaned)
                }
                // An answer may only claim what the broker recorded (ADR-027): an action needs a write, and
                // memory needs a memory_remember that ran (ADR-089). Both get one repair, then escalate.
                val unbacked = ActionClaims.unbacked(text.toString(), actions)
                if (unbacked.isNotEmpty()) {
                    val why = unbacked.joinToString("; ") { it.why }
                    emit(AgentEvent.Discarded(decision.tier, why))
                    claimed += unbacked
                    systemNote = ActionClaims.note(claimed, canRemember = specs.any { it.name == ActionClaims.REMEMBER_TOOL })
                    if (!claimRepaired) {
                        claimRepaired = true
                        repaired = true
                        continue
                    }
                    val next = router.escalate(route, decision, why)
                    if (next != null) {
                        emit(AgentEvent.Escalated(decision.tier, next.tier, why))
                        decision = next
                        continue
                    }
                    val warned = "$text\n\n${ActionClaims.warning(unbacked)}"
                    added += ChatMessage.Model(warned)
                    return@flow emit(
                        AgentEvent.Completed(
                            decision.tier, warned, added.toList(), actions.toList(), unverifiedClaim = true, tokens = sample(request, brain, text, usage), usage = usage,
                            decision = decision,
                        ),
                    )
                }
                added += ChatMessage.Model(text.toString())
                return@flow emit(
                    AgentEvent.Completed(
                        decision.tier, text.toString(), added.toList(), actions.toList(), tokens = sample(request, brain, text, usage), usage = usage, decision = decision,
                    ),
                )
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
                    is ToolOutcome.Ok -> actions += ActionRecord(call.name, outcome.summary, ActionRecord.Status.DONE, outcome.wrote, destination = outcome.destination)
                    is ToolOutcome.Rejected ->
                        actions += ActionRecord(call.name, outcome.summary, ActionRecord.Status.NOT_DONE, false, outcome.reason, outcome.destination)
                    is ToolOutcome.Failed ->
                        actions += ActionRecord(call.name, outcome.summary, ActionRecord.Status.FAILED, false, outcome.reason, outcome.destination)
                    is ToolOutcome.Invalid -> Unit // never ran, so it isn't an action
                }
                if (outcome !is ToolOutcome.Invalid) anyValid = true
                // Saved with the result, so later turns know what the model can still see. A call that never
                // ran holds only the broker's own words.
                // An app task picks its steps with an on-device model, and says which (ADR-098): the answer names it.
        val used = ((outcome as? ToolOutcome.Ok)?.result as? JsonObject)?.get("brain")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val tier = when (used) {
            BrainId.PRO.name -> Tier.PRO
            BrainId.BASE.name -> Tier.BASE
            else -> null
        }
        val untrusted = if (outcome is ToolOutcome.Ok || outcome is ToolOutcome.Failed) broker.untrustedContent(call.name) else null
                results += ToolResult(call.name, outcome.toResultJson(), untrusted)
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

/**
 * What the phone's own model is, for the chat (ADR-101): asked what model it was, Gemma answered "a large language model,
 * trained by Google". Only on-device brains read it; Gemini in the cloud never gets it.
 */
internal const val ON_DEVICE_NOTE =
    "You run entirely on this phone as Gemma 4, Google's open model, through LiteRT-LM. Nothing leaves the phone unless " +
        "the user agrees. When asked what model you are, say that."
