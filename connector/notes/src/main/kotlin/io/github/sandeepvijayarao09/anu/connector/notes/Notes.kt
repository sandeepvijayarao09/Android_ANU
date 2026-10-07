package io.github.sandeepvijayarao09.anu.connector.notes

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** A note the user keeps in Anu (ADR-093). */
data class Note(val id: Long, val title: String, val text: String, val updatedAtMillis: Long)

/**
 * Where notes are kept, so the tools can be tested without the database. `core:data` keeps them encrypted on the
 * phone; they never leave it.
 */
interface NoteStore {
    suspend fun create(title: String, text: String, atMillis: Long): Note

    /** Notes whose title or text holds every word of [query], newest first; the newest of all for a blank one. */
    suspend fun search(query: String, limit: Int): List<Note>

    /** Adds [text] on a new line at the end of the note [id], and gives it back as it now is. */
    suspend fun append(id: Long, text: String, atMillis: Long): Note
}

@Serializable
data class CreateNoteInput(
    @Description("The note's words, as the user gave them") val text: String,
    @Description("A short title, e.g. Groceries. Leave it out to use the first words") val title: String = "",
) {
    init {
        require(text.isNotBlank() && text.length <= MAX_TEXT) { "text must be 1-$MAX_TEXT characters" }
        require(title.length <= MAX_TITLE) { "title must be at most $MAX_TITLE characters" }
    }
}

@Serializable
data class SearchNotesInput(
    @Description("Words to look for in the notes' titles and text. Empty for the newest notes") val query: String = "",
) {
    init {
        require(query.length <= MAX_TITLE) { "query must be at most $MAX_TITLE characters" }
    }
}

@Serializable
data class AppendNoteInput(
    @Description("The title of the note to add to, e.g. Groceries") val title: String,
    @Description("The words to add at the end of the note") val text: String,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "title must be 1-$MAX_TITLE characters" }
        require(text.isNotBlank() && text.length <= MAX_TEXT) { "text must be 1-$MAX_TEXT characters" }
    }
}

/** What a note tool did, in its own words, and the note's title. */
@Serializable
data class NoteSaved(val said: String, val title: String)

@Serializable
data class NoteOut(val title: String, val text: String)

@Serializable
data class NotesOutput(val notes: List<NoteOut>)

/**
 * Notes are the user's own words on their own phone: nothing in them is from other people (P4), and changing one
 * changes only the user's data, so writing one is `WRITE_PRIVATE` (§7.1).
 */
abstract class NoteTool<I : Any, O : Any>(protected val store: NoteStore, protected val now: () -> Long) : Tool<I, O> {
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent: UntrustedContent? = null
}

class CreateNoteTool(store: NoteStore, now: () -> Long = System::currentTimeMillis) : NoteTool<CreateNoteInput, NoteSaved>(store, now) {
    override val name = "notes_create"
    override val description = "Save a new note on this phone"
    override val input: KSerializer<CreateNoteInput> = CreateNoteInput.serializer()
    override val output: KSerializer<NoteSaved> = NoteSaved.serializer()
    override val editable = listOf(
        EditableField("title", "Title", EditableField.Kind.LINE, optional = true),
        EditableField("text", "Note", EditableField.Kind.TEXT),
    )

    override fun risk(input: CreateNoteInput) = RiskTier.WRITE_PRIVATE
    override fun describe(input: CreateNoteInput) = "Save a note, \"${titleOf(input)}\""

    override suspend fun invoke(input: CreateNoteInput, context: ToolContext): NoteSaved {
        val note = store.create(titleOf(input), input.text.trim(), now())
        return NoteSaved("Saved the note \"${note.title}\".", note.title)
    }

    private fun titleOf(input: CreateNoteInput) = input.title.trim().ifEmpty { firstWords(input.text) }
}

class SearchNotesTool(store: NoteStore, now: () -> Long = System::currentTimeMillis) : NoteTool<SearchNotesInput, NotesOutput>(store, now) {
    override val name = "notes_search"
    override val description = "Find the user's notes on this phone by words in their title or text"
    override val input: KSerializer<SearchNotesInput> = SearchNotesInput.serializer()
    override val output: KSerializer<NotesOutput> = NotesOutput.serializer()

    override fun risk(input: SearchNotesInput) = RiskTier.READ
    override fun describe(input: SearchNotesInput) =
        if (input.query.isBlank()) "Look at your newest notes" else "Look for \"${input.query.trim()}\" in your notes"

    override suspend fun invoke(input: SearchNotesInput, context: ToolContext): NotesOutput =
        NotesOutput(store.search(input.query.trim(), MAX_FOUND).map { NoteOut(it.title, excerpt(it.text)) })
}

/**
 * Adds to a note found by its title, so a small model needs no ids and no search first: an exact title wins, else
 * the one note whose title holds the words. Anything else fails with the titles it could mean, so the model can ask.
 */
class AppendNoteTool(store: NoteStore, now: () -> Long = System::currentTimeMillis) : NoteTool<AppendNoteInput, NoteSaved>(store, now) {
    override val name = "notes_append"
    override val description = "Add words to the end of one of the user's notes, found by its title"
    override val input: KSerializer<AppendNoteInput> = AppendNoteInput.serializer()
    override val output: KSerializer<NoteSaved> = NoteSaved.serializer()
    override val editable = listOf(EditableField("text", "Add", EditableField.Kind.TEXT))

    override fun risk(input: AppendNoteInput) = RiskTier.WRITE_PRIVATE
    override fun describe(input: AppendNoteInput) = "Add to your note \"${input.title.trim()}\""

    override suspend fun invoke(input: AppendNoteInput, context: ToolContext): NoteSaved {
        val asked = input.title.trim()
        val found = store.search(asked, MAX_FOUND).filter { it.title.contains(asked, ignoreCase = true) }
        val note = found.singleOrNull { it.title.equals(asked, ignoreCase = true) } ?: found.singleOrNull()
            ?: error(
                if (found.isEmpty()) {
                    val titles = store.search("", MAX_FOUND).joinToString { "\"${it.title}\"" }
                    "no note is titled \"$asked\"" + if (titles.isEmpty()) "; there are no notes yet" else "; the newest are $titles"
                } else {
                    "more than one note's title has \"$asked\": ${found.joinToString { "\"${it.title}\"" }}; ask which"
                },
            )
        val updated = store.append(note.id, input.text.trim(), now())
        return NoteSaved("Added to the note \"${updated.title}\".", updated.title)
    }
}

/** The first line's first words, as a title: "Buy oat milk and eggs" for a note with no title. */
internal fun firstWords(text: String): String {
    val line = text.trim().lineSequence().first().trim()
    if (line.length <= TITLE_FROM_TEXT) return line
    val cut = line.take(TITLE_FROM_TEXT)
    return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length).trimEnd() + "…"
}

/** A long note's start, so a search result fits the on-device context (ADR-040). */
internal fun excerpt(text: String) = if (text.length <= MAX_EXCERPT) text else text.take(MAX_EXCERPT).trimEnd() + "…"

private const val MAX_TEXT = 10_000
private const val MAX_TITLE = 100
private const val TITLE_FROM_TEXT = 40
private const val MAX_EXCERPT = 400
private const val MAX_FOUND = 5
