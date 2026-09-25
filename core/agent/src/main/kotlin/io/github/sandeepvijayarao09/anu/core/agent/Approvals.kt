package io.github.sandeepvijayarao09.anu.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** What the user is asked to approve: the exact payload, not a paraphrase from the model (I2). */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    /** Written by the tool from the validated input, never by the model. */
    val summary: String,
    val payload: JsonObject,
    val risk: RiskTier,
    /** Why approval is needed, for example "invites other people" or "the request read untrusted content". */
    val reason: String,
)

sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision

    data class Denied(val reason: String = "The user declined") : ApprovalDecision
}

/** Suspends until the user decides (§7.2). */
fun interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): ApprovalDecision
}

/**
 * Holds pending approvals so a screen, and later a notification, can show them. The run stays
 * suspended until [decide] is called. Cancelling the run withdraws its request.
 */
class ApprovalCenter : ApprovalGate {
    private class Pending(val request: ApprovalRequest, val answer: CompletableDeferred<ApprovalDecision>)

    private val pending = MutableStateFlow<List<Pending>>(emptyList())
    private val mutable = MutableStateFlow<List<ApprovalRequest>>(emptyList())

    /** Requests waiting for the user, oldest first. */
    val requests: StateFlow<List<ApprovalRequest>> = mutable.asStateFlow()

    override suspend fun request(request: ApprovalRequest): ApprovalDecision {
        val entry = Pending(request, CompletableDeferred())
        update { it + entry }
        return try {
            entry.answer.await()
        } finally {
            update { list -> list.filterNot { it === entry } }
        }
    }

    fun decide(id: String, decision: ApprovalDecision) {
        pending.value.firstOrNull { it.request.id == id }?.answer?.complete(decision)
    }

    private fun update(change: (List<Pending>) -> List<Pending>) {
        pending.update(change)
        mutable.value = pending.value.map { it.request }
    }
}
