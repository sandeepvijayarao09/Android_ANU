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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextField
import io.github.sandeepvijayarao09.anu.core.ui.PrivacyDot
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
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
    onRevise: (String, JsonObject) -> Unit,
    onApproveCloud: () -> Unit,
    onDeclineCloud: () -> Unit,
) {
    state.approval?.let { request -> ApprovalSheet(request, onApprove, onDeny, onRevise) }
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

/**
 * The approval sheet (DESIGN.md). Edit changes the fields the tool allows; saving approves nothing. The
 * broker checks the change and asks again with exactly what will run, so the user always approves the
 * final payload (I2). A change that doesn't pass keeps the user's text, with the reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(request: ApprovalRequest, onApprove: (String) -> Unit, onDeny: (String) -> Unit, onRevise: (String, JsonObject) -> Unit) {
    val copy = approvalCopy(request)
    val colors = AnuTheme.colors
    val haptics = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    // The broker answers a change with a new request: the checked payload, or the same one with a problem.
    LaunchedEffect(request) {
        if (saving) {
            saving = false
            if (request.problem == null) editing = false
        }
    }
    // A decision is required: swiping the sheet away or tapping outside is not one.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheet,
        shape = AnuShapes.sheet,
        containerColor = colors.surface,
        scrimColor = colors.scrim,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(AnuShapes.sm).background(colors.surface2), contentAlignment = Alignment.Center) {
                    Icon(copy.icon, contentDescription = null, Modifier.size(22.dp), tint = colors.text)
                }
                Text(copy.question, Modifier.semantics { heading() }, style = AnuTheme.type.title, color = colors.text)
            }
            WhereLine(leaves = copy.leaves, text = copy.where, modifier = Modifier.padding(top = 14.dp))
            if (editing) {
                request.problem?.let { problem ->
                    Text(problem, Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = AnuTheme.type.callout, color = colors.errorText)
                }
                request.editable.forEach { field ->
                    AnuTextField(
                        draft[field.key].orEmpty(),
                        { draft = draft + (field.key to it) },
                        Modifier.padding(top = 14.dp),
                        label = field.label,
                        singleLine = field.kind != EditableField.Kind.TEXT,
                        minLines = if (field.kind == EditableField.Kind.TEXT) 4 else 1,
                        // Every list a tool offers today is of addresses.
                        keyboardOptions = if (field.kind == EditableField.Kind.LIST) KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false) else KeyboardOptions.Default,
                    )
                }
                Text(
                    "Anu checks your changes, then shows exactly what will happen. Nothing happens until you tap ${copy.verb}.",
                    Modifier.padding(top = 12.dp, start = 2.dp, end = 2.dp),
                    style = AnuTheme.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    color = colors.textMuted,
                )
                Spacer(Modifier.height(18.dp))
                AnuButton(if (saving) "Checking…" else "Save changes", onClick = {
                    val revised = revisedPayload(request.payload, request.editable, draft)
                    if (revised == request.payload) {
                        editing = false
                    } else {
                        saving = true
                        onRevise(request.id, revised)
                    }
                }, Modifier.fillMaxWidth(), enabled = !saving)
                Spacer(Modifier.height(10.dp))
                AnuButton("Cancel", onClick = { editing = false }, Modifier.fillMaxWidth(), secondary = true)
            } else {
                // The tool's own sentence and the exact payload, re-encoded from validated input by the broker (I2).
                Column(
                    Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(max = 320.dp).clip(AnuShapes.md).background(colors.background)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    if (request.edited) Text("Edited by you", Modifier.padding(top = 10.dp), style = AnuTheme.type.label, color = colors.textMuted)
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
                val decline = { haptics.performHapticFeedback(HapticFeedbackType.Reject); onDeny(request.id) }
                if (request.editable.isEmpty()) {
                    AnuButton(copy.decline, onClick = decline, Modifier.fillMaxWidth(), secondary = true)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnuButton("Edit", onClick = {
                            draft = draftOf(request.payload, request.editable)
                            editing = true
                        }, Modifier.weight(1f), secondary = true)
                        AnuButton(copy.decline, onClick = decline, Modifier.weight(1f), secondary = true)
                    }
                }
            }
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

/** The editable fields as the sheet shows them: a list joined with commas, a missing value as empty text. */
internal fun draftOf(payload: JsonObject, fields: List<EditableField>): Map<String, String> = fields.associate { field ->
    field.key to when (val value = payload[field.key]) {
        null, JsonNull -> ""
        is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
        is JsonPrimitive -> value.content
        is JsonObject -> value.toString()
    }
}

/**
 * The payload with the user's text in place: a list split at commas, an optional field emptied removed.
 * Every other field stays exactly as the broker encoded it, and the broker checks the result (§7.2).
 */
internal fun revisedPayload(payload: JsonObject, fields: List<EditableField>, draft: Map<String, String>): JsonObject {
    val result = payload.toMutableMap()
    fields.forEach { field ->
        val text = draft[field.key] ?: return@forEach
        when {
            field.kind == EditableField.Kind.LIST ->
                result[field.key] = JsonArray(text.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive))
            field.optional && text.isBlank() -> if (payload[field.key].let { it != null && it !is JsonNull }) result.remove(field.key)
            else -> result[field.key] = JsonPrimitive(text.trim())
        }
    }
    return JsonObject(result)
}
