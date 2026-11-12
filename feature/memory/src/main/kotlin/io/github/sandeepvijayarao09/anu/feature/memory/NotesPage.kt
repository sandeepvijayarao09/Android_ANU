package io.github.sandeepvijayarao09.anu.feature.memory

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.ui.AnuDialog
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuRoundButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuSearchField
import io.github.sandeepvijayarao09.anu.core.ui.AnuSpacing
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A note Anu keeps for the user (ADR-093), as the Notes app shows it. */
data class ShelfNote(val id: Long, val title: String, val text: String, val updatedAtMillis: Long)

/** The user's notes, in Anu's own encrypted store, for the Notes app (ADR-133, ADR-134). */
interface NoteShelf {
    /** Every note, newest first. */
    val notes: Flow<List<ShelfNote>>

    suspend fun delete(id: Long)

    /** A note the user wrote in the Notes app; gives back its id (ADR-134). */
    suspend fun create(title: String, text: String): Long

    suspend fun update(id: Long, title: String, text: String)
}

/** Which note the editor shows: one already kept, or a new one, which exists only once it has words. */
private sealed interface Editing {
    data class Kept(val id: Long) : Editing

    data object New : Editing
}

/**
 * Anu's notes app (ADR-133, ADR-134; DESIGN.md "Notes"), from the Memory tab: the notes newest first, a search, "+"
 * for a new one, and an editor that saves as you type. Talk makes and adds to the same notes ("Add oat milk to my
 * shopping list").
 */
@Composable
internal fun NotesPage(viewModel: MemoryViewModel, notes: List<ShelfNote>, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingNew by rememberSaveable { mutableStateOf(false) }
    val editing = when {
        editingNew -> Editing.New
        editingId != null -> Editing.Kept(editingId!!)
        else -> null
    }
    if (editing != null) {
        // The editor handles BACK itself, so it saves first.
        val close = { editingId = null; editingNew = false }
        NoteEditor(
            viewModel,
            note = (editing as? Editing.Kept)?.let { kept -> notes.find { it.id == kept.id } },
            new = editing == Editing.New,
            sentToday = sentToday,
            onOpenPrivacy = onOpenPrivacy,
            onCreated = { id -> editingNew = false; editingId = id },
            onClose = close,
        )
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(notes, query) { notes.filter { matches(it, query) } }
    AnuPage(
        "Notes",
        sentToday,
        onOpenPrivacy,
        back = "Memory",
        onBack = onBack,
        subtitle = "Kept on this phone, encrypted. Write one here, or ask Anu to.",
        actions = { AnuRoundButton(AnuIcons.Plus, "New note", onClick = { editingNew = true }) },
    ) {
        if (notes.isNotEmpty()) {
            item(key = "search") { AnuSearchField(query, { query = it }, Modifier.padding(horizontal = AnuSpacing.xl, vertical = 4.dp)) }
        }
        when {
            notes.isEmpty() -> item(key = "empty") {
                Text(
                    "Tap + to write a note, or say “Add a note called Groceries: oat milk and eggs” in Talk.",
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                    style = AnuTheme.type.callout,
                    color = AnuTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            shown.isEmpty() -> item(key = "none") {
                Text(
                    "No note has “${query.trim()}”.",
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                    style = AnuTheme.type.callout,
                    color = AnuTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            else -> item(key = "notes") {
                Column {
                    Group(Modifier.padding(top = 4.dp)) {
                        shown.forEachIndexed { index, note ->
                            if (index > 0) GroupDivider()
                            GroupRow(
                                title = note.title.ifBlank { preview(note.text) ?: "New note" },
                                subtitle = if (note.title.isBlank()) null else preview(note.text),
                                value = whenChanged(note.updatedAtMillis),
                                chevron = true,
                                onClick = { editingId = note.id },
                            )
                        }
                    }
                    GroupFooter("Notes never leave this phone. Anu reads one only when you ask about it.")
                }
            }
        }
    }
}

/**
 * One note, editable in place: a large title and the text, saved half a second after the last change and again on
 * the way out. Copy and Delete sit in the bar.
 */
@Composable
private fun NoteEditor(
    viewModel: MemoryViewModel,
    note: ShelfNote?,
    new: Boolean,
    sentToday: Int,
    onOpenPrivacy: () -> Unit,
    onCreated: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val colors = AnuTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A kept note that was deleted (here, or by Talk) is gone: close.
    LaunchedEffect(new, note == null) { if (!new && note == null) onClose() }
    if (!new && note == null) return

    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var text by rememberSaveable(note?.id) { mutableStateOf(note?.text.orEmpty()) }
    var saved by remember(note?.id) { mutableStateOf(true) }
    var deleting by remember { mutableStateOf(false) }
    val save: suspend () -> Unit = {
        val id = viewModel.saveNote(note?.id, title, text)
        if (note == null && id != null) onCreated(id)
        saved = true
    }
    // Saved half a second after the last change.
    LaunchedEffect(title, text) {
        if (title == note?.title.orEmpty() && text == note?.text.orEmpty()) return@LaunchedEffect
        saved = false
        delay(SAVE_AFTER_MS)
        save()
    }
    val leave = { scope.launch { if (!saved) save(); onClose() }; Unit }
    BackHandler(onBack = leave)

    val focus = remember { FocusRequester() }
    if (new) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AnuPage(
        title = "",
        sentToday = sentToday,
        onOpenPrivacy = onOpenPrivacy,
        back = "Notes",
        onBack = leave,
        actions = {
            if (note != null) {
                AnuTextButton("Copy", onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, text))
                })
                AnuTextButton("Delete", onClick = { deleting = true })
            }
        },
    ) {
        item(key = "editor") {
            Column(Modifier.fillMaxWidth().padding(horizontal = AnuSpacing.xl)) {
                BasicTextField(
                    title,
                    { title = it.replace("\n", " ") },
                    Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = AnuTheme.type.title.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    decorationBox = { field ->
                        if (title.isEmpty()) Text("Title", style = AnuTheme.type.title, color = colors.textFaint)
                        field()
                    },
                )
                WhereLine(
                    leaves = false,
                    text = (if (saved) "Saved" else "Saving…") + " · Kept on this phone",
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                BasicTextField(
                    text,
                    { text = it },
                    Modifier.fillMaxWidth().heightIn(min = 320.dp),
                    textStyle = AnuTheme.type.body.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { field ->
                        if (text.isEmpty()) Text("Write something", style = AnuTheme.type.body, color = colors.textFaint)
                        field()
                    },
                )
            }
        }
    }

    if (deleting && note != null) {
        AnuDialog(
            title = "Delete “${title.ifBlank { "this note" }}”?",
            text = "The note is gone from this phone. This can't be undone.",
            confirm = "Delete",
            onConfirm = { deleting = false; viewModel.deleteNote(note.id); onClose() },
            dismiss = "Keep",
            onDismiss = { deleting = false },
            destructive = true,
        )
    }
}

private const val SAVE_AFTER_MS = 500L

/** Whether [note] holds every word of [query], in its title or text. */
internal fun matches(note: ShelfNote, query: String): Boolean {
    val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    val all = "${note.title}\n${note.text}".lowercase()
    return words.all(all::contains)
}

/** The note's first words, on one line. */
internal fun preview(text: String): String? = text.lines().map(String::trim).filter(String::isNotEmpty).joinToString(" · ").take(80).ifBlank { null }

/** The time for a note changed today, else its date. */
private fun whenChanged(millis: Long): String {
    val at = Instant.ofEpochMilli(millis)
    val zone = ZoneId.systemDefault()
    return if (at.atZone(zone).toLocalDate() == LocalDate.now(zone)) shortTime(at) else shortDate(at)
}
