package io.github.sandeepvijayarao09.anu.connector.notes

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteToolsTest {
    /** Notes in memory, searched as the database does: every word, in the title or the text, newest first. */
    private class MemoryNotes : NoteStore {
        val notes = mutableListOf<Note>()

        override suspend fun create(title: String, text: String, atMillis: Long) =
            Note(notes.size + 1L, title, text, atMillis).also { notes += it }

        override suspend fun search(query: String, limit: Int) = notes
            .filter { note -> query.split(" ").filter(String::isNotBlank).all { "${note.title} ${note.text}".contains(it, ignoreCase = true) } }
            .sortedByDescending { it.updatedAtMillis }
            .take(limit)

        override suspend fun append(id: Long, text: String, atMillis: Long): Note {
            val index = notes.indexOfFirst { it.id == id }
            return notes[index].let { it.copy(text = it.text + "\n" + text, updatedAtMillis = atMillis) }.also { notes[index] = it }
        }
    }

    private val store = MemoryNotes()
    private var clock = 1_000L
    private val ctx = ToolContext("run", "key")
    private val create = CreateNoteTool(store) { clock++ }
    private val search = SearchNotesTool(store) { clock++ }
    private val append = AppendNoteTool(store) { clock++ }

    @Test
    fun `a note without a title takes its first words`() = runTest {
        val saved = create.invoke(CreateNoteInput("Call the plumber about the kitchen sink before the weekend"), ctx)
        assertEquals("Call the plumber about the kitchen sink…", saved.title)
        assertEquals("Save a note, \"Groceries\"", create.describe(CreateNoteInput("oat milk", title = "Groceries")))
        assertEquals(RiskTier.WRITE_PRIVATE, create.risk(CreateNoteInput("x")))
    }

    @Test
    fun `search finds by words, and a blank one gives the newest`() = runTest {
        create.invoke(CreateNoteInput("oat milk, eggs", title = "Groceries"), ctx)
        create.invoke(CreateNoteInput("Gate code is on the fridge", title = "House"), ctx)
        assertEquals(listOf("Groceries"), search.invoke(SearchNotesInput("eggs"), ctx).notes.map { it.title })
        assertEquals(listOf("House", "Groceries"), search.invoke(SearchNotesInput(), ctx).notes.map { it.title })
    }

    @Test
    fun `append finds the note by its title`() = runTest {
        create.invoke(CreateNoteInput("oat milk", title = "Groceries"), ctx)
        val saved = append.invoke(AppendNoteInput("groceries", "eggs"), ctx)
        assertEquals("Added to the note \"Groceries\".", saved.said)
        assertEquals("oat milk\neggs", store.notes.single().text)
    }

    @Test
    fun `an exact title wins over others that hold it`() = runTest {
        create.invoke(CreateNoteInput("a", title = "Work"), ctx)
        create.invoke(CreateNoteInput("b", title = "Work trip"), ctx)
        append.invoke(AppendNoteInput("Work", "c"), ctx)
        assertEquals("a\nc", store.notes.first().text)
    }

    @Test
    fun `a title that matches none or several says which notes there are`() = runTest {
        create.invoke(CreateNoteInput("a", title = "Trip to Pune"), ctx)
        create.invoke(CreateNoteInput("b", title = "Trip to Goa"), ctx)
        val none = assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { append.invoke(AppendNoteInput("Groceries", "eggs"), ctx) } }
        assertTrue(none.message!!, none.message!!.startsWith("no note is titled \"Groceries\"; the newest are \"Trip to Goa\""))
        val two = assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { append.invoke(AppendNoteInput("Trip", "x"), ctx) } }
        assertTrue(two.message!!, two.message!!.endsWith("ask which"))
    }

    @Test
    fun `long notes are cut in results`() {
        assertEquals(401, excerpt("a".repeat(1_000)).length)
        assertEquals("short", excerpt("short"))
    }
}
