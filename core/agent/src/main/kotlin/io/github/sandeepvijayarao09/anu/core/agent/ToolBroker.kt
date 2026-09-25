package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
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
 */
sealed interface ToolOutcome {
    /** [wrote] is true when the call changed something, so an answer may truthfully say it acted. */
    data class Ok(val result: JsonElement, val summary: String = "", val wrote: Boolean = false) : ToolOutcome

    /** Unknown tool or arguments that don't match the schema. Never executed (I6). The model may try again. */
    data class Invalid(val reason: String) : ToolOutcome

    /** Blocked by policy, or the user said no. */
    data class Rejected(val reason: String, val summary: String = "") : ToolOutcome

    /** The tool itself threw. */
    data class Failed(val reason: String, val summary: String = "") : ToolOutcome

    fun toResultJson(): JsonElement = when (this) {
        is Ok -> result as? JsonObject ?: buildJsonObject { put("result", result) }
        is Invalid -> error("invalid_call", reason)
        is Rejected -> error("not_allowed", reason)
        is Failed -> error("tool_failed", reason)
    }

    private fun error(code: String, message: String) = buildJsonObject {
        put("error", code)
        put("message", message)
    }
}

/** What the broker knows about the run it serves. [tainted] becomes true once untrusted content has been read (P4). */
class RunState(
    val runId: String,
    /** Identifies the user turn. The same turn re-sent (for example after consent) reuses its idempotency keys. */
    val turnKey: String,
    var brainLocation: BrainLocation,
    val cloudConsented: Boolean,
) {
    var tainted: Boolean = false
        internal set

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
 * 4. asks for approval of the exact payload when required (I2). A timeout counts as a denial;
 * 5. executes, stores and audits.
 */
class ToolBroker(
    tools: List<Tool<*, *>>,
    private val approvals: ApprovalGate,
    private val results: ToolResultStore = InMemoryToolResultStore(),
    private val audit: ToolAudit = ToolAudit.None,
    private val approvalTimeoutMillis: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val byName: Map<String, Tool<*, *>> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "Tool names must be unique" }
    }

    /** Schemas of the named tools, so each skill offers only the tools it needs (§7.3). */
    fun specs(names: Collection<String>): List<ToolSpec> = names.map { name ->
        requireNotNull(byName[name]) { "No tool named $name" }.spec()
    }

    suspend fun execute(call: ToolCall, run: RunState): ToolOutcome {
        val tool = byName[call.name]
            ?: return ToolOutcome.Invalid("There is no tool named ${call.name}. Available: ${byName.keys.sorted().joinToString()}")
        return executeTyped(tool, call, run)
    }

    private suspend fun <I : Any, O : Any> executeTyped(tool: Tool<I, O>, call: ToolCall, run: RunState): ToolOutcome {
        val input = try {
            json.decodeFromJsonElement(tool.input, call.arguments)
        } catch (e: SerializationException) {
            return ToolOutcome.Invalid("The arguments don't match ${tool.name}'s schema: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return ToolOutcome.Invalid("The arguments for ${tool.name} are not valid: ${e.message}")
        }
        // Tools may reject input while assessing it, for example an end time before the start.
        val (risk, summary) = try {
            tool.risk(input) to tool.describe(input)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome.Invalid("The arguments for ${tool.name} are not valid: ${e.message}")
        }
        suspend fun record(outcome: String, approved: Boolean?) =
            audit.record(ToolAuditEntry(run.runId, tool.name, risk, summary, outcome, approved, clock()))

        if (run.brainLocation == BrainLocation.CLOUD && DataClass.SENSITIVE in tool.dataClasses && !run.cloudConsented) {
            record("blocked: sensitive data for the cloud", null)
            return ToolOutcome.Rejected("${tool.name} returns sensitive data, which stays on this phone", summary)
        }

        // The payload the user approves is re-encoded from the validated input, not the model's raw arguments.
        val payload = json.encodeToJsonElement(tool.input, input) as JsonObject
        val key = idempotencyKey(run.turnKey, tool.name, payload)
        if (risk != RiskTier.READ) {
            results.get(key)?.let { stored ->
                record("replayed", null)
                return ToolOutcome.Ok(stored, summary, wrote = true)
            }
        }

        val approvalReason = when (risk) {
            RiskTier.READ -> null
            RiskTier.WRITE_PRIVATE -> if (run.tainted) "This request read content written by others" else null
            RiskTier.WRITE_EXTERNAL -> "Other people will see this"
            RiskTier.DESTRUCTIVE -> "This can't be undone"
        }
        if (approvalReason != null) {
            val decision = withTimeoutOrNull(approvalTimeoutMillis) {
                approvals.request(ApprovalRequest(key, tool.name, summary, payload, risk, approvalReason))
            } ?: ApprovalDecision.Denied("No answer in time")
            if (decision is ApprovalDecision.Denied) {
                record("denied: ${decision.reason}", approved = false)
                return ToolOutcome.Rejected("The user did not approve this: ${decision.reason}", summary)
            }
        }

        val output = try {
            tool.invoke(input, ToolContext(run.runId, key))
        } catch (e: CancellationException) {
            throw e // never swallow cancellation (§18)
        } catch (e: Exception) {
            record("failed: ${e.message}", approvalReason?.let { true })
            return ToolOutcome.Failed(e.message ?: e::class.simpleName ?: "error", summary)
        }
        val result = json.encodeToJsonElement(tool.output, output)
        if (risk != RiskTier.READ) results.put(key, result)
        if (tool.returnsUntrustedContent) run.tainted = true
        run.dataClassesSeen += tool.dataClasses
        record("ok", approvalReason?.let { true })
        return ToolOutcome.Ok(result, summary, wrote = risk != RiskTier.READ)
    }

    private fun idempotencyKey(turnKey: String, tool: String, payload: JsonObject): String =
        MessageDigest.getInstance("SHA-256").digest("$turnKey|$tool|$payload".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // Unknown keys are ignored (they can't cause an action). Types and required fields are strict.
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
