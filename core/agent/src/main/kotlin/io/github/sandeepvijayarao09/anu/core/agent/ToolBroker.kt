package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The broker's verdict on one call. Every outcome goes back to the model as the tool's result.
 * [summary] is the tool's own description of the call (never the model's words), shown to the user (ADR-027).
 * [destination] is the third party the call's input went to, or would have ([Tool.sendsTo]).
 */
sealed interface ToolOutcome {
    /** [wrote] is true when the call changed something, so an answer may truthfully say it acted. */
    data class Ok(val result: JsonElement, val summary: String = "", val wrote: Boolean = false, val destination: String? = null) : ToolOutcome

    /** Unknown tool or arguments that don't match the schema. Never executed (I6). The model may try again. */
    data class Invalid(val reason: String) : ToolOutcome

    /**
     * Blocked by policy, or the user said no. [code] tells the model which: it read a plain
     * "not_allowed" for a declined reply as a system rule, as the emulator showed.
     */
    data class Rejected(val reason: String, val summary: String = "", val code: String = "not_allowed", val destination: String? = null) : ToolOutcome

    /** The tool itself threw. */
    data class Failed(val reason: String, val summary: String = "", val destination: String? = null) : ToolOutcome

    fun toResultJson(): JsonElement = when (this) {
        is Ok -> result as? JsonObject ?: buildJsonObject { put("result", result) }
        is Invalid -> error("invalid_call", reason)
        is Rejected -> error(code, reason)
        is Failed -> error("tool_failed", reason)
    }

    private fun error(code: String, message: String) = buildJsonObject {
        put("error", code)
        put("message", message)
    }
}

/**
 * What the broker knows about the run it serves. The run is tainted while the model can see content from
 * other people (P4): what the chat already shows when the run starts ([inView]), and what tools return.
 */
class RunState(
    val runId: String,
    /** Identifies the user turn. The same turn re-sent (for example after consent) reuses its idempotency keys. */
    val turnKey: String,
    var brainLocation: BrainLocation,
    /**
     * The user said yes to the cloud for this turn: to its message and the facts on the consent sheet. It never
     * lets a tool's sensitive results go to the cloud (ADR-087).
     */
    val cloudConsented: Boolean,
    inView: Set<UntrustedContent> = emptySet(),
) {
    private val seen = inView.toMutableSet()

    /** The kinds of untrusted content the model can see, so the approval sheet can name them (§7.2). */
    val untrusted: Set<UntrustedContent> get() = seen.toSet()

    val tainted: Boolean get() = seen.isNotEmpty()

    internal fun taint(kind: UntrustedContent) {
        seen += kind
    }

    /** Data classes of everything tools have returned, so escalation to the cloud can re-check consent. */
    val dataClassesSeen: MutableSet<DataClass> = mutableSetOf()
}

/** Results of side-effecting calls by idempotency key, so a retried call never runs twice (§7.2). */
interface ToolResultStore {
    suspend fun get(key: String): JsonElement?

    suspend fun put(key: String, result: JsonElement)
}

class InMemoryToolResultStore : ToolResultStore {
    private val results = mutableMapOf<String, JsonElement>()

    override suspend fun get(key: String): JsonElement? = results[key]

    override suspend fun put(key: String, result: JsonElement) {
        results[key] = result
    }
}

data class ToolAuditEntry(
    val runId: String,
    val toolName: String,
    val risk: RiskTier,
    val summary: String,
    val outcome: String,
    /** Null when no approval was needed. */
    val approved: Boolean?,
    val atMillis: Long,
)

/** Where the broker records what happened (§7.2). Suspends, so a database write never blocks the caller. */
fun interface ToolAudit {
    suspend fun record(entry: ToolAuditEntry)

    companion object {
        val None = ToolAudit { }
    }
}

/**
 * The only way a tool call is executed (ARCHITECTURE §7.2, P2). In order, it:
 * 1. validates the arguments against the tool's schema (I6);
 * 2. applies policy: risk tier, taint, and sensitive data bound for the cloud;
 * 3. replays a stored result instead of repeating a side effect;
 * 4. asks for approval of the exact payload when required (I2). A timeout counts as a denial. The user
 *    may edit the fields a tool allows; the edit is checked like the model's arguments, and the user
 *    approves exactly what will run;
 * 5. executes, stores and audits.
 */
class ToolBroker(
    tools: List<Tool<*, *>>,
    private val approvals: ApprovalGate,
    private val results: ToolResultStore = InMemoryToolResultStore(),
    private val audit: ToolAudit = ToolAudit.None,
    private val approvalTimeoutMillis: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Tools that come and go while the app runs, such as those of the user's MCP servers (ADR-046).
     * The same policy applies to them. A fixed tool wins if a name is ever shared.
     */
    private val dynamicTools: () -> Collection<Tool<*, *>> = { emptyList() },
) {
    private val byName: Map<String, Tool<*, *>> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "Tool names must be unique" }
    }

    private fun find(name: String): Tool<*, *>? = byName[name] ?: dynamicTools().firstOrNull { it.name == name }

    /**
     * The kind of untrusted content a result from [toolName] holds. A tool that's no longer there, such as
     * a removed MCP server's, counts as untrusted, since nothing can vouch for what it returned (P4).
     */
    fun untrustedContent(toolName: String): UntrustedContent? {
        val tool = find(toolName) ?: return UntrustedContent.UNKNOWN
        return tool.untrustedContent
    }

    /**
     * Whether a result from [toolName] can hold sensitive data (S2), such as memory's facts. Such a result never
     * goes to the cloud, consent or not (ADR-087). Only Anu's own tools declare sensitive data, so a tool that's no
     * longer there, such as a removed MCP server's, didn't.
     */
    fun returnsSensitive(toolName: String): Boolean = find(toolName)?.let { DataClass.SENSITIVE in it.dataClasses } == true

    /** Schemas of the named tools, so each skill offers only the tools it needs (§7.3). */
    fun specs(names: Collection<String>): List<ToolSpec> = names.map { name ->
        requireNotNull(find(name)) { "No tool named $name" }.spec()
    }

    suspend fun execute(call: ToolCall, run: RunState): ToolOutcome {
        val tool = find(call.name)
            ?: return ToolOutcome.Invalid("There is no tool named ${call.name}. Available: ${byName.keys.sorted().joinToString()}")
        return executeTyped(tool, call, run)
    }

    private suspend fun <I : Any, O : Any> executeTyped(tool: Tool<I, O>, call: ToolCall, run: RunState): ToolOutcome {
        var current = try {
            assess(tool, call.arguments)
        } catch (e: SerializationException) {
            return ToolOutcome.Invalid("The arguments don't match ${tool.name}'s schema: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return ToolOutcome.Invalid("The arguments for ${tool.name} are not valid: ${e.message}")
        }
        suspend fun record(outcome: String, approved: Boolean?) =
            audit.record(ToolAuditEntry(run.runId, tool.name, current.risk, current.summary, outcome, approved, clock()))

        // Consent covers the message and the facts on the consent sheet, never what a tool returns (ADR-087).
        if (run.brainLocation == BrainLocation.CLOUD && DataClass.SENSITIVE in tool.dataClasses) {
            record("blocked: sensitive data for the cloud", null)
            return ToolOutcome.Rejected("${tool.name} returns sensitive data, which stays on this phone", current.summary, destination = current.destination)
        }

        val proposed = idempotencyKey(run.turnKey, tool.name, current.payload)
        var key = proposed
        if (current.risk != RiskTier.READ) {
            results.get(key)?.let { stored ->
                record("replayed", null)
                return ToolOutcome.Ok(stored, current.summary, wrote = true, destination = current.destination)
            }
        }

        // Ask until the user approves, declines or doesn't answer. An edit approves nothing: the broker checks
        // it like the model's arguments and asks again with exactly what will run (I2).
        var reason = approvalReason(current.risk, current.destination, run.untrusted)
        val asked = reason != null
        var edited = false
        var problem: String? = null
        while (reason != null) {
            val request = ApprovalRequest(
                key, tool.name, current.summary, current.payload, current.risk, reason, current.destination, run.untrusted,
                tool.editable, edited, problem, current.rows,
            )
            when (val decision = withTimeoutOrNull(approvalTimeoutMillis) { approvals.request(request) }) {
                null -> {
                    record("no answer in time", approved = false)
                    return ToolOutcome.Rejected("Nobody answered the approval in time, so nothing was done.", current.summary, code = "no_answer", destination = current.destination)
                }
                is ApprovalDecision.Denied -> {
                    record("denied: ${decision.reason}", approved = false)
                    return ToolOutcome.Rejected(
                        "The user was shown this and declined (${decision.reason}), so nothing was done. Don't try again unless they ask.",
                        current.summary,
                        code = "declined",
                        destination = current.destination,
                    )
                }
                ApprovalDecision.Approved -> reason = null
                is ApprovalDecision.Revised -> {
                    val revised = try {
                        revise(tool, current, decision.payload)
                    } catch (e: IllegalArgumentException) {
                        problem = e.message ?: "That change isn't valid."
                        continue
                    } ?: continue
                    current = revised
                    key = idempotencyKey(run.turnKey, tool.name, current.payload)
                    edited = true
                    problem = null
                    if (current.risk != RiskTier.READ) {
                        results.get(key)?.let { stored ->
                            record("replayed", null)
                            return ToolOutcome.Ok(stored, current.summary, wrote = true, destination = current.destination)
                        }
                    }
                    // The user always sees the final payload before it runs, even if it no longer needs asking.
                    reason = approvalReason(current.risk, current.destination, run.untrusted) ?: "You changed this"
                }
            }
        }

        val output = try {
            tool.invoke(current.input, ToolContext(run.runId, key))
        } catch (e: CancellationException) {
            throw e // never swallow cancellation (§18)
        } catch (e: Exception) {
            // A failure's message can carry the other side's words, such as a service's error text.
            tool.untrustedContent?.let(run::taint)
            record("failed: ${e.message}", if (asked) true else null)
            return ToolOutcome.Failed(e.message ?: e::class.simpleName ?: "error", current.summary, current.destination)
        }
        val result = json.encodeToJsonElement(tool.output, output)
        if (current.risk != RiskTier.READ) {
            results.put(key, result)
            // A retry of the model's original call replays what the user's edit did, instead of acting again.
            if (key != proposed) results.put(proposed, result)
        }
        tool.untrustedContent?.let(run::taint)
        run.dataClassesSeen += tool.dataClasses
        record(if (edited) OK_EDITED else "ok", if (asked) true else null)
        return ToolOutcome.Ok(result, current.summary, wrote = current.risk != RiskTier.READ, destination = current.destination)
    }

    /** A call's input after validation, what the tool says about it, and the payload the user sees, in the tool's words when it has them. */
    private class Assessed<I : Any>(
        val input: I,
        val risk: RiskTier,
        val summary: String,
        val destination: String?,
        val payload: JsonObject,
        val rows: List<PayloadRow>?,
    )

    /**
     * Decodes and checks arguments (I6), then asks the tool about them. Tools may reject input while
     * assessing it, for example an end time before the start. Throws [IllegalArgumentException] with the
     * reason; a [SerializationException] means the arguments don't match the schema.
     */
    private fun <I : Any> assess(tool: Tool<I, *>, arguments: JsonObject): Assessed<I> {
        val input = json.decodeFromJsonElement(tool.input, arguments)
        // The payload the user approves is re-encoded from the validated input, not the raw arguments.
        // Arguments run in order, so a tool's rows come after its description, as [Tool.rows] promises.
        return Assessed(
            input, tool.risk(input), tool.describe(input), tool.sendsTo(input), json.encodeToJsonElement(tool.input, input) as JsonObject,
            tool.rows(input),
        )
    }

    /**
     * The user's change, checked like the model's arguments: only the tool's editable fields may differ,
     * and the result must pass the tool's own checks. Null when nothing changed. Throws with a reason the
     * user can act on.
     */
    private fun <I : Any> revise(tool: Tool<I, *>, current: Assessed<I>, edited: JsonObject): Assessed<I>? {
        val changed = (current.payload.keys + edited.keys).filter { current.payload[it] != edited[it] }
        if (changed.isEmpty()) return null
        val allowed = tool.editable.map { it.key }.toSet()
        require(changed.all { it in allowed }) {
            if (allowed.isEmpty()) "This can't be changed here." else "Only ${tool.editable.joinToString { it.label.lowercase() }} can be changed here."
        }
        // An edit that checks out to the same payload, such as a cleared field that was already empty, is no change.
        return assess(tool, edited).takeIf { it.payload != current.payload }
    }

    /**
     * Why a call needs the user's approval, or null when it runs by itself (§7.1). When the model can see
     * content from other people, the reason names its kind, never the content: its sender or subject is
     * written by outsiders, and could pose as Anu on the one screen that must be trusted (§7.2).
     */
    private fun approvalReason(risk: RiskTier, destination: String?, untrusted: Set<UntrustedContent>): String? {
        val shows = if (untrusted.isEmpty()) null else shownIn(untrusted)
        return when (risk) {
            // Injected text could otherwise send the user's data to a third party without asking.
            RiskTier.READ -> if (shows != null && destination != null) "This sends data to $destination".andShows(shows, untrusted) else null
            RiskTier.WRITE_PRIVATE -> if (shows != null) "Anu asks because $shows, ${whoWrote(untrusted)}. Check this closely" else null
            // A service or store the user added isn't "other people": say where it goes (seen on the emulator).
            RiskTier.WRITE_EXTERNAL ->
                (if (destination != null) "This goes to $destination, outside your phone" else "Other people will see this").andShows(shows, untrusted)
            RiskTier.DESTRUCTIVE ->
                (if (destination != null) "This goes to $destination, and may not be undoable there" else "This can't be undone").andShows(shows, untrusted)
        }
    }

    private fun String.andShows(shows: String?, untrusted: Set<UntrustedContent>): String =
        if (shows == null) this else "$this. ${shows.replaceFirstChar(Char::uppercase)}, ${whoWrote(untrusted)}, so check this closely"

    /** A photo, a recording or a screen can hold other people's words; a message or a reply is theirs. */
    private fun whoWrote(untrusted: Set<UntrustedContent>): String =
        if (untrusted.all { it in MEDIA }) "which can hold other people's words" else "which others can write"

    private fun idempotencyKey(turnKey: String, tool: String, payload: JsonObject): String =
        MessageDigest.getInstance("SHA-256").digest("$turnKey|$tool|$payload".toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Content the user chose to show, which can still hold other people's words. */
        private val MEDIA = setOf(UntrustedContent.PHOTO, UntrustedContent.RECORDING, UntrustedContent.SCREEN, UntrustedContent.DOCUMENT)

        /** The audit's outcome for a call that ran with the user's own changes. */
        const val OK_EDITED = "ok, edited by you"

        /** "this chat shows an email and a store's results", in a fixed order. */
        internal fun shownIn(untrusted: Set<UntrustedContent>): String {
            val names = untrusted.sorted().map { kind ->
                when (kind) {
                    UntrustedContent.EMAIL -> "an email"
                    UntrustedContent.MESSAGE -> "a message"
                    UntrustedContent.CALENDAR -> "calendar events"
                    UntrustedContent.STORE -> "a store's results"
                    UntrustedContent.SERVICE -> "a reply from one of your services"
                    UntrustedContent.UNKNOWN -> "a reply from a service you removed"
                    UntrustedContent.PHOTO -> "a photo"
                    UntrustedContent.RECORDING -> "a recording"
                    UntrustedContent.SCREEN -> "your screen"
                    UntrustedContent.DOCUMENT -> "a PDF"
                }
            }
            val listed = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
            return "this chat shows $listed"
        }

        // Unknown keys are ignored (they can't cause an action). Types and required fields are strict.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
