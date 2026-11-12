package io.github.sandeepvijayarao09.anu.feature.memory

import io.github.sandeepvijayarao09.anu.core.memory.LearnResult
import io.github.sandeepvijayarao09.anu.core.memory.MemoryInspector
import io.github.sandeepvijayarao09.anu.core.memory.MemoryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesTest {
    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    /** A store that suspends on every call, as Room does, so a save resumes after it. */
    private class Shelf : NoteShelf {
        val kept = MutableStateFlow<List<ShelfNote>>(emptyList())
        override val notes: Flow<List<ShelfNote>> = kept

        override suspend fun delete(id: Long) {
            yield()
            kept.value = kept.value.filterNot { it.id == id }
        }

        override suspend fun create(title: String, text: String): Long {
            yield()
            val id = kept.value.size + 1L
            kept.value = listOf(ShelfNote(id, title, text, 0)) + kept.value
            return id
        }

        override suspend fun update(id: Long, title: String, text: String) {
            yield()
            kept.value = kept.value.map { if (it.id == id) it.copy(title = title, text = text) else it }
        }
    }

    private object NoMemory : MemoryInspector {
        override val state: Flow<MemoryState> = flowOf(MemoryState())
        override suspend fun learnNow() = LearnResult()
        override suspend fun add(text: String) = 0
        override suspend fun delete(factId: Long) = Unit
        override suspend fun confirm(factId: Long) = Unit
        override suspend fun correct(factId: Long, value: String) = Unit
        override suspend fun forgetSource(eventId: Long) = Unit
    }

    @Test
    fun `a new note is made once it has words, then the same note is updated`() = runTest {
        val shelf = Shelf()
        val model = MemoryViewModel(NoMemory, shelf)
        assertNull("an empty new note isn't kept", model.saveNote(null, "", ""))
        val id = model.saveNote(null, "Packing", "")
        assertEquals(1L, id)
        assertEquals(id, model.saveNote(id, " Packing ", "passport, charger"))
        assertEquals(listOf(ShelfNote(1, "Packing", "passport, charger", 0)), shelf.kept.value)
    }

    @Test
    fun `search finds every word in the title or text`() {
        val note = ShelfNote(1, "Packing", "passport, charger", 0)
        assertTrue(matches(note, "pack CHARGER"))
        assertTrue(matches(note, ""))
        assertFalse(matches(note, "socks"))
        assertEquals("passport · charger", preview("passport\n\n charger "))
    }
}
