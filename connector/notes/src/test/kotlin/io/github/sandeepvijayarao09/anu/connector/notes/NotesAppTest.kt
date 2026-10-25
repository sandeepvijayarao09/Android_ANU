package io.github.sandeepvijayarao09.anu.connector.notes

import io.github.sandeepvijayarao09.anu.core.agent.AppDriver
import io.github.sandeepvijayarao09.anu.core.agent.AppScreen
import io.github.sandeepvijayarao09.anu.core.agent.LaunchedApp
import io.github.sandeepvijayarao09.anu.core.agent.ScreenElement
import io.github.sandeepvijayarao09.anu.core.agent.ScreenElement.Kind
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesAppTest {
    /** Samsung Notes' screens as uiautomator read them on the S26 Ultra, advancing as Anu acts. */
    private class FakeNotes(private val screens: MutableList<AppScreen>, override val ready: Boolean = true) : AppDriver {
        val actions = mutableListOf<String>()
        var hidden = false
        override var current: AppScreen? = null
        override val stops = emptyFlow<Unit>()

        override fun openSetup() = Unit

        override suspend fun open(name: String): LaunchedApp? = null

        override suspend fun openPackage(packageName: String) = LaunchedApp("Notes", packageName).also { actions += "open $packageName" }

        override suspend fun read(): AppScreen = screens.first().also { current = it }

        private fun next() {
            if (screens.size > 1) screens.removeAt(0)
        }

        override suspend fun tap(id: Int) {
            actions += "tap ${current!!.element(id)!!.label}"
            next()
        }

        override suspend fun type(id: Int, text: String) {
            actions += "type ${current!!.element(id)!!.label}: $text"
            next()
        }

        override suspend fun enter(id: Int) = Unit
        override suspend fun scroll(down: Boolean) = Unit

        override suspend fun back() {
            actions += "back"
            next()
        }

        override fun showProgress(app: String, line: String) = Unit

        override fun hideProgress() {
            hidden = true
        }
    }

    private fun screen(vararg elements: Pair<Kind, String>) =
        AppScreen("Samsung Notes", "com.samsung.android.app.notes", elements.mapIndexed { i, (kind, label) -> ScreenElement(i + 1, kind, label) })

    private val list = screen(Kind.BUTTON to "Navigation drawer", Kind.TEXT to "No notes", Kind.BUTTON to "Create note")
    private val editor = screen(Kind.BUTTON to "Navigate up", Kind.TEXT to "Title", Kind.BUTTON to "Insert", Kind.FIELD to "Text field")
    private val titled = screen(Kind.FIELD to "Title", Kind.BUTTON to "Favorites", Kind.FIELD to "Text field")
    private val written = screen(Kind.BUTTON to "Navigate up", Kind.FIELD to "Text field")

    @Test
    fun `it creates the note, fills its title and text, and saves it`() = runTest {
        val app = FakeNotes(mutableListOf(list, editor, titled, titled, written))
        SamsungNotesMirror(app) { true }.save("Groceries", "Milk, eggs")
        assertEquals(
            listOf("open com.samsung.android.app.notes", "tap Create note", "tap Title", "type Title: Groceries", "type Text field: Milk, eggs", "tap Navigate up"),
            app.actions,
        )
        assertTrue(app.hidden)
    }

    @Test
    fun `it goes back to the list from a note left open`() = runTest {
        val app = FakeNotes(mutableListOf(written, list, editor, titled, titled, written))
        SamsungNotesMirror(app) { true }.save("Groceries", "Milk, eggs")
        assertEquals(listOf("open com.samsung.android.app.notes", "tap Navigate up", "tap Create note"), app.actions.take(3))
    }

    @Test
    fun `it closes the editor's first-run tip before writing`() = runTest {
        val tip = screen(Kind.TEXT to "Touch and hold, then drag to move this toolbar.")
        val app = FakeNotes(mutableListOf(list, tip, editor, titled, titled, written))
        SamsungNotesMirror(app) { true }.save("Groceries", "Milk, eggs")
        assertEquals("back", app.actions[2])
        assertEquals("tap Navigate up", app.actions.last())
    }

    @Test
    fun `notes_create keeps the note in Anu when Samsung Notes fails, and says so`() = runTest {
        val store = MemoryStore()
        val tool = CreateNoteTool(store, SamsungNotesMirror(FakeNotes(mutableListOf(screen(Kind.TEXT to "Loading")))) { true }) { 1L }
        val saved = tool.invoke(CreateNoteInput("Milk, eggs", "Groceries"), ToolContext("run", "key"))
        assertEquals(1, store.notes.size)
        assertEquals("Saved the note \"Groceries\" in Anu, but not in Samsung Notes: the Create note button wasn't on Samsung Notes's screen.", saved.said)
    }

    @Test
    fun `the approval sheet asks only when Samsung Notes will be written`() {
        val on = CreateNoteTool(MemoryStore(), SamsungNotesMirror(FakeNotes(mutableListOf(list))) { true })
        val off = CreateNoteTool(MemoryStore(), SamsungNotesMirror(FakeNotes(mutableListOf(list), ready = false)) { true })
        val input = CreateNoteInput("Milk, eggs", "Groceries")
        assertEquals("Anu will also write it in Samsung Notes, tapping and typing there", on.approvalNote(input))
        assertNull(off.approvalNote(input))
        assertNull(CreateNoteTool(MemoryStore()).approvalNote(input))
    }

    private class MemoryStore : NoteStore {
        val notes = mutableListOf<Note>()
        override suspend fun create(title: String, text: String, atMillis: Long) = Note(notes.size + 1L, title, text, atMillis).also { notes += it }
        override suspend fun search(query: String, limit: Int) = notes.take(limit)
        override suspend fun append(id: Long, text: String, atMillis: Long) = notes.first { it.id == id }
    }
}
