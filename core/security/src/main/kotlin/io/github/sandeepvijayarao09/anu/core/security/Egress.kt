package io.github.sandeepvijayarao09.anu.core.security

import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** One record of data leaving the device (I3). It describes what was sent, never the content itself. */
data class LedgerEntry(
    val at: Instant,
    val destination: String,
    val purpose: String,
    val dataClasses: Set<DataClass>,
    /** Distinct values replaced by placeholders, per kind. */
    val redacted: Map<PiiKind, Int>,
    val characters: Int,
)

interface PrivacyLedger {
    suspend fun record(entry: LedgerEntry)

    val entries: StateFlow<List<LedgerEntry>>
}

/** Holds entries in memory until the encrypted database (`core:data`) backs the ledger. */
class InMemoryLedger : PrivacyLedger {
    private val state = MutableStateFlow(emptyList<LedgerEntry>())
    override val entries: StateFlow<List<LedgerEntry>> = state.asStateFlow()

    override suspend fun record(entry: LedgerEntry) = state.update { it + entry }
}

/**
 * The only path from a brain request to the cloud (§12.2, I3). Cloud brains call [prepare] before
 * sending. It redacts every part of the request and writes a ledger entry. The router has already
 * enforced consent (§6, Stage A).
 */
class EgressGate(private val ledger: PrivacyLedger, private val clock: () -> Instant = Instant::now) {

    class Prepared(val request: BrainRequest, val redaction: RedactionSession)

    suspend fun prepare(request: BrainRequest, destination: String): Prepared {
        require(DataClass.SECRET !in request.dataClasses) { "Secrets never leave the vault (I1)" }
        val session = RedactionSession()
        val redacted = request.copy(
            system = request.system?.let(session::redact),
            transcript = request.transcript.map { it.redactWith(session) },
        )
        val found = session.counts.keys.mapTo(mutableSetOf()) { it.dataClass }
        ledger.record(
            LedgerEntry(
                at = clock(),
                destination = destination,
                purpose = request.purpose,
                dataClasses = request.dataClasses + found,
                redacted = session.counts,
                characters = redacted.characterCount(),
            ),
        )
        return Prepared(redacted, session)
    }

    private fun ChatMessage.redactWith(session: RedactionSession): ChatMessage = when (this) {
        is ChatMessage.User -> ChatMessage.User(session.redact(text))
        is ChatMessage.Model -> ChatMessage.Model(
            session.redact(text),
            toolCalls.map { it.copy(arguments = session.redact(it.arguments)) },
        )
        is ChatMessage.Tool -> ChatMessage.Tool(results.map { it.copy(result = session.redact(it.result)) })
    }

    private fun BrainRequest.characterCount(): Int = (system?.length ?: 0) + transcript.sumOf { message ->
        when (message) {
            is ChatMessage.User -> message.text.length
            is ChatMessage.Model -> message.text.length + message.toolCalls.sumOf { it.arguments.toString().length }
            is ChatMessage.Tool -> message.results.sumOf { it.result.toString().length }
        }
    }
}
