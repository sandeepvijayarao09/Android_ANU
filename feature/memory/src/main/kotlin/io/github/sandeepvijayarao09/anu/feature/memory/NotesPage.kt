package io.github.sandeepvijayarao09.anu.feature.memory

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuDialog
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A note Anu keeps for the user (ADR-093), as the Notes page shows it. */
data class ShelfNote(val id: Long, val title: String, val text: String, val updatedAtMillis: Long)

/** The user's notes, in Anu's own encrypted store, for the Notes page (ADR-133). */
interface NoteShelf {
    /** Every note, newest first. */
    val notes: Flow<List<ShelfNote>>

    suspend fun delete(id: Long)
}

/**
 * The Notes page (ADR-133, DESIGN.md "Notes"): the notes Anu keeps, newest first, from the Memory tab. Notes are made
 * and added to in Talk ("Add oat milk to my shopping list"); here they're read, copied and deleted.
 */
@Composable
internal fun NotesPage(notes: List<ShelfNote>, sentToday: Int, onOpenPrivacy: () -> Unit, onDelete: (Long) -> Unit, onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    AnuPage(
        "Notes",
        sentToday,
        onOpenPrivacy,
        back = "Memory",
        onBack = onBack,
        subtitle = "Kept on this phone, encrypted. Ask Anu to make one or add to it.",
    ) {
        if (notes.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Say “Add a note called Groceries: oat milk and eggs” in Talk, and it's kept here.",
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                    style = AnuTheme.type.callout,
                    color = AnuTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            item(key = "notes") {
                Column {
                    Group {
                        notes.forEachIndexed { index, note ->
                            if (index > 0) GroupDivider()
                            GroupRow(
                                title = note.title.ifBlank { "Untitled" },
                                subtitle = preview(note.text),
                                value = whenChanged(note.updatedAtMillis),
                                chevron = true,
                                onClick = { open = note.id },
                            )
                        }
                    }
                    GroupFooter("Notes never leave this phone. Anu reads one only when you ask about it.")
                }
            }
        }
    }
    open?.let { id ->
        val note = notes.find { it.id == id }
        // A deleted note is gone: close its sheet.
        LaunchedEffect(note == null) { if (note == null) open = null }
        if (note != null) NoteSheet(note, onDelete = { onDelete(note.id) }, onDismiss = { open = null })
    }
}

@Composable
private fun NoteSheet(note: ShelfNote, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val colors = AnuTheme.colors
    val context = LocalContext.current
    var deleting by remember(note.id) { mutableStateOf(false) }
    var copied by remember(note.id) { mutableStateOf(false) }
    AnuSheet(onDismiss) { _ ->
        SheetTitle(note.title.ifBlank { "Untitled" }, overline = "Changed ${longDate(Instant.ofEpochMilli(note.updatedAtMillis))}")
        WhereLine(leaves = false, text = "Kept on this phone", modifier = Modifier.padding(top = 10.dp))
        SelectionContainer {
            Text(note.text.ifBlank { "This note is empty." }, Modifier.padding(top = 16.dp), style = AnuTheme.type.body, color = colors.text)
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnuButton(
                if (copied) "Copied" else "Copy",
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(note.title, note.text))
                    copied = true
                },
                Modifier.weight(1f),
                secondary = true,
            )
            AnuButton("Delete", onClick = { deleting = true }, Modifier.weight(1f), secondary = true, destructive = true)
        }
    }
    if (deleting) {
        AnuDialog(
            title = "Delete “${note.title.ifBlank { "Untitled" }}”?",
            text = "The note is gone from this phone. This can't be undone.",
            confirm = "Delete",
            onConfirm = { deleting = false; onDelete() },
            dismiss = "Keep",
            onDismiss = { deleting = false },
            destructive = true,
        )
    }
}

/** The note's first words, on one line. */
internal fun preview(text: String): String? = text.lines().map(String::trim).filter(String::isNotEmpty).joinToString(" · ").take(80).ifBlank { null }

/** The time for a note changed today, else its date. */
private fun whenChanged(millis: Long): String {
    val at = Instant.ofEpochMilli(millis)
    val zone = ZoneId.systemDefault()
    return if (at.atZone(zone).toLocalDate() == LocalDate.now(zone)) shortTime(at) else shortDate(at)
}
