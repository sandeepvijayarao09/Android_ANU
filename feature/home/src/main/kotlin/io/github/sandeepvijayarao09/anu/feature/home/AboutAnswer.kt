package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AnswerDetails
import io.github.sandeepvijayarao09.anu.core.router.Guard
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.DetailPanel
import io.github.sandeepvijayarao09.anu.core.ui.DetailRow
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.SheetNote
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
import java.util.Locale
import kotlin.math.roundToLong

/**
 * "About this answer" for one answer: its saved details, and its facts as memory holds them now, by id. A
 * fact's text is null once it has been deleted or changed (ADR-071).
 */
data class AboutAnswer(val details: AnswerDetails, val facts: List<Pair<Long, String?>>)

/**
 * The sheet's first line (DESIGN.md, design review D7): where the user's data went for this answer. An `away`
 * dot when anything left the phone, with where it went.
 */
internal fun whereItWent(details: AnswerDetails): Pair<Boolean, String> {
    if (details.tier == Tier.CLOUD) return true to "Sent to Gemini (Google)"
    val left = details.actions.filter { it.status == ActionRecord.Status.DONE && leavesPhone(it.tool) }
    if (left.isEmpty()) return false to "Nothing left your phone"
    val places = left.map { destinationOf(it) }.distinct()
    val items = if (left.size == 1) "1 item" else "${left.size} items"
    return true to "$items left your phone · to ${joinAnd(places)}"
}

/** The second line: which model answered, and where it ran. A phone command needed none (design review D20). */
internal fun whoAnswered(details: AnswerDetails): String = if (details.direct) "Done on this phone · no model needed" else when (details.tier) {
    Tier.CLOUD -> "Gemini · in the cloud"
    else -> details.tier.model + " · " + (backendName(details.backend)?.let { "$it, on this phone" } ?: "on this phone")
}

/** Why the phone's guard shaped this answer, when it did (design review D11, ADR-072). */
internal fun guardReason(details: AnswerDetails): String? {
    val guard = details.guard ?: return null
    val why = if (guard == Guard.HEAT) "the phone was warm" else "the battery was low"
    return when {
        details.cut -> "Kept short because $why."
        details.tier == Tier.BASE -> "The lighter model, because $why."
        else -> null
    }
}

/** How fast it was, as rows: exact times from the phone's clock, and token rates marked when estimated. */
internal fun speedRows(details: AnswerDetails): List<Pair<String, String>> = buildList {
    if (details.direct) {
        details.totalMs?.let { add("Done in" to seconds(it)) }
        return@buildList
    }
    details.heardMs?.let { add("Writing it down" to seconds(it)) }
    details.thoughtMs?.let { add("Thinking" to seconds(it)) }
    details.firstWordMs?.let { add("First word" to seconds(it)) }
    if (details.tier != Tier.CLOUD) {
        rate(details.read, details.readMs)?.let { add("Reading" to perSecond(it, details.counted)) }
        rate(details.written, details.writeMs)?.let { add("Writing" to perSecond(it, details.counted)) }
    }
    details.totalMs?.let { add("Total" to seconds(it)) }
}

/** Tools that send something outside the phone: Gmail, replies, services and stores (as [Receipt.leftPhone]). */
private fun leavesPhone(tool: String) =
    tool.startsWith("email_") || tool.startsWith("mcp_") || tool.startsWith("shop_") || tool == "messages_reply"

private fun destinationOf(action: ActionRecord): String = action.destination ?: when {
    action.tool.startsWith("email_") -> "your Gmail"
    action.tool == "messages_reply" -> "your messaging app"
    else -> "a service you added"
}

private fun backendName(backend: String?): String? = when {
    backend == null -> null
    backend.startsWith("NPU_TENSOR") -> "Tensor TPU"
    backend.startsWith("NPU") -> "NPU"
    else -> backend
}

private fun joinAnd(items: List<String>): String =
    if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

private fun rate(tokens: Int?, ms: Long?): Double? =
    if (tokens == null || tokens <= 0 || ms == null || ms <= 0) null else tokens * 1_000.0 / ms

private fun perSecond(rate: Double, counted: Boolean): String {
    val value = if (rate >= 10) rate.roundToLong().toString() else String.format(Locale.US, "%.1f", rate)
    return if (counted) "$value tokens/s" else "about $value tokens/s"
}

internal fun seconds(ms: Long): String = when {
    ms < 10_000 -> String.format(Locale.US, "%.1f s", ms / 1_000.0)
    ms < 60_000 -> "${(ms / 1_000.0).roundToLong()} s"
    else -> "${ms / 60_000} min ${(ms % 60_000) / 1_000} s"
}

/**
 * About this answer (DESIGN.md, ADR-071): where your data went first, then the model and where it ran, the
 * facts it was given (each opens in Memory), what it did, and how fast it was.
 */
@Composable
internal fun AboutSheet(about: AboutAnswer, onOpenMemory: (Long?) -> Unit, onOpenPrivacy: () -> Unit, onDismiss: () -> Unit) {
    val colors = AnuTheme.colors
    val details = about.details
    val cloud = details.tier == Tier.CLOUD
    AnuSheet(onDismiss) { close ->
        SheetTitle("About this answer")
        val (leaves, where) = whereItWent(details)
        WhereLine(leaves, where, Modifier.padding(top = 10.dp))
        Text(whoAnswered(details), Modifier.padding(top = 4.dp), style = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal), color = colors.textMuted)
        val notes = listOfNotNull(details.escalatedFrom?.let { first -> "Answered after ${first.model}'s answer didn't pass its checks." }, guardReason(details))
        notes.forEach { note ->
            Text(note, Modifier.padding(top = 2.dp), style = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal), color = colors.textMuted)
        }

        if (about.facts.isNotEmpty() || details.summaryShown) {
            Heading(if (cloud) "Facts you shared" else "Facts it was given")
            DetailPanel {
                about.facts.forEach { (id, fact) ->
                    GroupRow(
                        title = fact ?: "A fact you've since deleted or changed",
                        titleColor = if (fact == null) colors.textMuted else null,
                        chevron = fact != null,
                        onClick = if (fact != null) ({ close(); onOpenMemory(id) }) else null,
                    )
                }
                if (details.summaryShown) {
                    GroupRow(
                        title = "Your memory summary",
                        subtitle = "What Anu knows about you, in short",
                        chevron = true,
                        onClick = { close(); onOpenMemory(null) },
                    )
                }
            }
        }

        if (details.actions.isNotEmpty()) {
            Heading("What it did")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details.actions.forEach { ReceiptRow(receiptOf(it)) }
            }
        }

        val speed = speedRows(details)
        if (speed.isNotEmpty()) {
            Heading("Speed")
            DetailPanel {
                speed.forEachIndexed { i, (label, value) -> DetailRow(label, value, first = i == 0) }
            }
            if (!cloud && !details.counted && speed.any { it.first == "Reading" || it.first == "Writing" }) {
                SheetNote("Reading and writing speeds are estimates.")
            }
        }
        if (cloud) AnuTextButton("View in Privacy report", onClick = { close(); onOpenPrivacy() }, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp).semantics { heading() },
        style = AnuTheme.type.label.copy(fontWeight = FontWeight(600)),
        color = AnuTheme.colors.textMuted,
    )
}

internal fun receiptOf(action: ActionRecord) = Receipt(
    action.tool,
    action.summary,
    when (action.status) {
        ActionRecord.Status.DONE -> Receipt.Status.DONE
        ActionRecord.Status.NOT_DONE -> Receipt.Status.NOT_DONE
        ActionRecord.Status.FAILED -> Receipt.Status.FAILED
    },
)
