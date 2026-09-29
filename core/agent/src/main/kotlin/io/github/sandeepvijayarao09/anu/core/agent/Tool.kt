package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.serialization.KSerializer

/** How much a tool call can affect the world (ARCHITECTURE §7.1). The Tool Broker's policy is keyed on it. */
enum class RiskTier {
    /** No side effects. Runs automatically. */
    READ,

    /** Changes only the user's own data, and can be undone. Automatic, unless the run is tainted. */
    WRITE_PRIVATE,

    /** Visible to others, or leaves the user's control. Always needs approval of the exact payload (I2). */
    WRITE_EXTERNAL,

    /** Deletes or can't be undone. Always needs approval. */
    DESTRUCTIVE,
}

/**
 * A payload field the user may change on the approval sheet before approving (§7.2): one line of text,
 * free text, or a list typed with commas. An [optional] field left empty is removed.
 */
data class EditableField(val key: String, val label: String, val kind: Kind, val optional: Boolean = false) {
    enum class Kind { LINE, TEXT, LIST }
}

/** Passed to every tool call. [idempotencyKey] is stable across retries of the same call (§7.2). */
data class ToolContext(val runId: String, val idempotencyKey: String)

/**
 * A capability the model may ask for (§7.1). Tools never decide whether they run: the [ToolBroker] does.
 * The parameter schema is generated from [input], never hand-written.
 */
interface Tool<I : Any, O : Any> {
    /** snake_case `<connector>_<verb>`, for example `calendar_create_event`. */
    val name: String
    val description: String
    val input: KSerializer<I>
    val output: KSerializer<O>

    /** The personal data this tool's results contain (§12.1). */
    val dataClasses: Set<DataClass>

    /**
     * The kind of content from other people this tool returns, such as email bodies or event titles, or null
     * when it returns only the user's own data (P4). While the model can see it, Anu asks before acting (§7.2).
     */
    val untrustedContent: UntrustedContent?

    /** Risk can depend on the input. For example, an event with attendees sends invites (§9). */
    fun risk(input: I): RiskTier

    /**
     * Where this call sends its input, when that is a third party rather than the phone or the user's
     * own account: for example an MCP server's or a store's host (ADR-046, ADR-047). Null otherwise.
     * Once a run is tainted, even a READ call that sends data away needs approval, so text written by
     * others can't make Anu send the user's data somewhere (§7.2).
     */
    fun sendsTo(input: I): String? = null

    /** One sentence that says exactly what this call will do. It is shown on the approval sheet. */
    fun describe(input: I): String

    /**
     * Fields the user may change on the approval sheet (§7.2), such as an email's subject and message.
     * The broker checks the edited payload like the model's own arguments, then asks again with exactly
     * what will run (I2). Identifiers, times and anything that picks a target stay out. Empty: no Edit.
     */
    val editable: List<EditableField> get() = emptyList()

    suspend fun invoke(input: I, context: ToolContext): O

    fun spec(): ToolSpec = ToolSpec.of(name, description, input)
}
