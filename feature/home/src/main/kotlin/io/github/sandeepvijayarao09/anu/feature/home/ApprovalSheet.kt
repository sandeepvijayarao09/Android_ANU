package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.PrivacyDot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The approval of an exact payload (I2) and the cloud consent (§12.2), as sheets (DESIGN.md). */
@Composable
internal fun ConversationSheets(
    state: ChatUiState,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onApproveCloud: () -> Unit,
    onDeclineCloud: () -> Unit,
) {
    state.approval?.let { request -> ApprovalSheet(request, onApprove, onDeny) }
    if (state.consentFor != null) CloudConsentSheet(onApproveCloud, onDeclineCloud)
}

/** How a request reads on its sheet: the question, the verb, and where the payload goes. */
internal data class ApprovalCopy(
    val question: String,
    val verb: String,
    val decline: String,
    val icon: ImageVector,
    val leaves: Boolean,
    val where: String,
    val notes: List<String>,
)

internal fun approvalCopy(request: ApprovalRequest): ApprovalCopy {
    val tool = request.toolName
    val invites = tool == "calendar_create_event" && request.risk == RiskTier.WRITE_EXTERNAL
    data class Words(val question: String, val verb: String, val decline: String, val icon: ImageVector)
    val words = when {
        tool == "email_send" -> Words("Send this email?", "Send", "Don't send", AnuIcons.Mail)
        tool == "email_create_draft" -> Words("Save this draft?", "Save draft", "Don't save", AnuIcons.Mail)
        tool == "messages_reply" -> Words("Send this reply?", "Send", "Don't send", AnuIcons.Message)
        invites -> Words("Send these invites?", "Send invites", "Don't send", AnuIcons.Calendar)
        tool == "calendar_create_event" -> Words("Add this event?", "Add", "Don't add", AnuIcons.Calendar)
        tool == "memory_forget" -> Words("Forget this?", "Forget", "Keep it", AnuIcons.Memory)
        tool == "memory_remember" -> Words("Remember this?", "Remember", "Don't", AnuIcons.Memory)
        tool == "shop_checkout" -> Words("Start this checkout?", "Start checkout", "Don't start", AnuIcons.Bag)
        tool == "shop_search" -> Words("Search your stores?", "Search", "Don't search", AnuIcons.Bag)
        tool.startsWith("mcp_") -> Words("Send this to a service?", "Send", "Don't send", AnuIcons.Service)
        else -> Words("Allow this?", "Allow", "Don't allow", AnuIcons.Sparkle)
    }
    val destination = request.destination
    val (leaves, where) = when {
        destination != null -> true to "Leaves your phone · to $destination"
        tool.startsWith("email_") -> true to "Leaves your phone · to your Gmail"
        tool == "messages_reply" -> true to "Leaves your phone · through your messaging app"
        invites -> true to "Leaves your phone · invites go out from your calendar"
        else -> false to "Stays on this phone"
    }
    val notes = buildList {
        if (request.tainted) add("Written after reading someone else's content. Read it closely.")
        if (tool == "shop_checkout" && destination != null) add("Anu won't pay. You finish on $destination.")
    }
    return ApprovalCopy(words.question, words.verb, words.decline, words.icon, leaves, where, notes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(request: ApprovalRequest, onApprove: (String) -> Unit, onDeny: (String) -> Unit) {
    val copy = approvalCopy(request)
    val colors = AnuTheme.colors
    val haptics = LocalHapticFeedback.current
    // A decision is required: swiping the sheet away or tapping outside is not one.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheet,
        shape = AnuShapes.sheet,
        containerColor = colors.surface,
        scrimColor = colors.scrim,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(AnuShapes.sm).background(colors.surface2), contentAlignment = Alignment.Center) {
                    Icon(copy.icon, contentDescription = null, Modifier.size(22.dp), tint = colors.text)
                }
                Text(copy.question, Modifier.semantics { heading() }, style = AnuTheme.type.title, color = colors.text)
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PrivacyDot(away = copy.leaves)
                Text(copy.where, style = AnuTheme.type.label, color = if (copy.leaves) colors.awayText else colors.textMuted)
            }
            // The tool's own sentence and the exact payload, re-encoded from validated input by the broker (I2).
            Column(
                Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(max = 320.dp).clip(AnuShapes.md).background(colors.background)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text(request.summary, Modifier.padding(vertical = 10.dp), style = AnuTheme.type.bodyStrong, color = colors.text)
                payloadRows(request.payload).forEach { (label, value) ->
                    HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
                    if (label == null) {
                        Text(value, Modifier.padding(vertical = 10.dp), style = AnuTheme.type.body, color = colors.text)
                    } else {
                        Row(Modifier.padding(vertical = 10.dp)) {
                            Text(label, Modifier.width(76.dp), style = AnuTheme.type.callout, color = colors.textMuted)
                            Text(value, style = AnuTheme.type.callout, color = colors.text)
                        }
                    }
                }
            }
            (listOf(request.reason.trimEnd('.') + ". Nothing happens until you tap ${copy.verb}.") + copy.notes).forEach { line ->
                Text(line, Modifier.padding(top = 12.dp, start = 2.dp, end = 2.dp), style = AnuTheme.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = colors.textMuted)
            }
            Spacer(Modifier.height(18.dp))
            AnuButton(copy.verb, onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onApprove(request.id) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            AnuButton(copy.decline, onClick = { haptics.performHapticFeedback(HapticFeedbackType.Reject); onDeny(request.id) }, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudConsentSheet(onApprove: () -> Unit, onDecline: () -> Unit) {
    val colors = AnuTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDecline, sheetState = sheet, shape = AnuShapes.sheet, containerColor = colors.surface, scrimColor = colors.scrim) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(AnuShapes.sm).background(colors.surface2), contentAlignment = Alignment.Center) {
                    Icon(AnuIcons.Sparkle, contentDescription = null, Modifier.size(22.dp), tint = colors.text)
                }
                Text("Use Gemini for this?", Modifier.semantics { heading() }, style = AnuTheme.type.title, color = colors.text)
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PrivacyDot(away = true)
                Text("Leaves your phone · to Gemini (Google)", style = AnuTheme.type.label, color = colors.awayText)
            }
            Text(
                "This needs the cloud model. Your message goes to Google with email addresses, phone and card numbers " +
                    "replaced, and the send is recorded in the Privacy report.",
                Modifier.padding(top = 16.dp),
                style = AnuTheme.type.body,
                color = colors.text,
            )
            Spacer(Modifier.height(20.dp))
            AnuButton("Send to Gemini", onClick = onApprove, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            AnuButton("Keep on this phone", onClick = onDecline, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

/**
 * The payload as rows: a label and its value, or a paragraph for long text such as an email body.
 * Every field is shown, so the user sees every value that will be used.
 */
internal fun payloadRows(payload: JsonObject): List<Pair<String?, String>> = payload.filterValues { it !is JsonNull }.map { (key, value) ->
    val text = when (value) {
        is JsonArray -> value.joinToString("; ") { element -> plain(element) }.ifEmpty { "none" }
        else -> plain(value)
    }
    val paragraph = key in PARAGRAPH_KEYS || (text.length > 60 && value is JsonPrimitive)
    (if (paragraph) null else humanize(key)) to text
}

/** One line per field, so the user sees every value that will be used. */
internal fun payloadLines(payload: JsonObject): List<String> = payload.filterValues { it !is JsonNull }.map { (key, value) ->
    val shown = when (value) {
        is JsonArray -> value.joinToString("; ") { element -> plain(element) }.ifEmpty { "none" }
        else -> plain(value)
    }
    "$key: $shown"
}

/** A value as words: objects in a list, such as a store order's items, read "item_id: x, quantity: 1". */
private fun plain(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.content
    is JsonObject -> value.filterValues { it !is JsonNull }.entries.joinToString(", ") { (key, inner) -> "$key: ${plain(inner)}" }
    is JsonArray -> value.joinToString(", ") { plain(it) }
}

private val PARAGRAPH_KEYS = setOf("body", "text", "message", "content", "note", "description")

/** "replyToThreadId" and "item_id" read as "Reply to thread id" and "Item id". */
private fun humanize(key: String): String {
    val spaced = key.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
    return spaced.replaceFirstChar { it.uppercase() }
}
