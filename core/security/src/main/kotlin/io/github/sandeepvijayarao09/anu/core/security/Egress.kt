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
        // Photos never leave the phone (MEDIA-1). Redaction rebuilds messages from their text, so it would drop a
        // photo silently; the gate refuses first, before any ledger entry or request (A3).
        require(request.transcript.none { it is ChatMessage.User && it.photos.isNotEmpty() }) { "Photos never leave the phone (MEDIA-1)" }
        // A voice note's words are a recording's, so they stay too (MEDIA-1, ADR-078); redaction would drop them silently.
        require(request.transcript.none { it is ChatMessage.User && it.voiceNote != null }) { "Voice notes never leave the phone (MEDIA-1)" }
        // A screen the user asked about is theirs to show Anu, never to send (MEDIA-1, ADR-083).
        require(request.transcript.none { it is ChatMessage.User && it.screen != null }) { "Screens never leave the phone (MEDIA-1)" }
        // A PDF the user attached, and the text read from it, stay too (MEDIA-1, ADR-095); redaction would drop them silently.
        require(request.transcript.none { it is ChatMessage.User && it.document != null }) { "PDFs never leave the phone (MEDIA-1)" }
        val session = RedactionSession()
        val redacted = request.copy(
            system = request.system?.let(session::redact),
            transcript = request.transcript.map { it.redactWith(session) },
            // Facts the user chose to share on the consent sheet leave too, so they pass the same way (ADR-054).
            sharedFacts = request.sharedFacts.map(session::redact),
        )
        val found = session.counts.keys.mapTo(mutableSetOf()) { it.dataClass }
        val facts = request.sharedFacts.size
        ledger.record(
            LedgerEntry(
                at = clock(),
                destination = destination,
                purpose = request.purpose + if (facts == 0) "" else " · $facts ${if (facts == 1) "fact" else "facts"} from memory",
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

    private fun BrainRequest.characterCount(): Int = (system?.length ?: 0) + sharedFacts.sumOf { it.length } + transcript.sumOf { message ->
        when (message) {
            is ChatMessage.User -> message.text.length
            is ChatMessage.Model -> message.text.length + message.toolCalls.sumOf { it.arguments.toString().length }
            is ChatMessage.Tool -> message.results.sumOf { it.result.toString().length }
        }
    }
}
