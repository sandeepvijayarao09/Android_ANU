package io.github.sandeepvijayarao09.anu.feature.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    private class FakeShelf : VaultShelf {
        val items = mutableListOf(
            entry("b", "Test API key"),
            entry("a", "Gemini API key"),
        )
        /** How many reveals find the vault locked before one works. */
        var lockedFor = 0
        var reveals = 0
        var failing = false

        override suspend fun items(): List<VaultEntry> = if (failing) error("disk") else items.toList()

        override suspend fun delete(id: String) {
            items.removeAll { it.id == id }
        }

        override suspend fun reveal(id: String): Revealed {
            reveals++
            return if (lockedFor-- > 0) Revealed.Locked else Revealed.Value("secret-$id")
        }
    }

    private val shelf = FakeShelf()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lists the items by label, without their secrets`() = runTest {
        val model = VaultViewModel(shelf)
        model.refresh()
        assertEquals(listOf("Gemini API key", "Test API key"), model.state.value.items.map { it.label })
        assertTrue(model.state.value.loaded)
        assertNull(model.state.value.shownValue)
    }

    @Test
    fun `a reveal shows the value until the sheet closes`() = runTest {
        val model = VaultViewModel(shelf).apply { refresh() }
        model.reveal("a")
        assertEquals("a", model.state.value.shownId)
        assertEquals("secret-a", model.state.value.shownValue)
        model.hide()
        assertNull(model.state.value.shownValue)
        assertNull(model.state.value.shownId)
    }

    @Test
    fun `a locked vault asks for the prompt again, and the retry shows the value`() = runTest {
        shelf.lockedFor = 1
        val model = VaultViewModel(shelf).apply { refresh() }
        model.reveal("a")
        assertEquals("a", model.state.value.unlockAgainFor)
        assertNull(model.state.value.shownValue)
        // The page prompts again, and the user passes it.
        model.reveal("a")
        assertEquals("secret-a", model.state.value.shownValue)
        assertNull(model.state.value.unlockAgainFor)
        assertEquals(2, shelf.reveals)
    }

    @Test
    fun `a dismissed prompt shows nothing`() = runTest {
        shelf.lockedFor = 1
        val model = VaultViewModel(shelf).apply { refresh() }
        model.reveal("a")
        model.unlockDismissed()
        assertNull(model.state.value.unlockAgainFor)
        assertNull(model.state.value.shownValue)
    }

    @Test
    fun `a phone that can't ask says how to show values`() = runTest {
        val model = VaultViewModel(shelf).apply { refresh() }
        model.unlockDismissed("Set a screen lock")
        assertEquals("Set a screen lock", model.state.value.error)
        assertNull(model.state.value.shownValue)
        assertEquals(0, shelf.reveals)
    }

    @Test
    fun `delete removes the item and anything shown of it`() = runTest {
        val model = VaultViewModel(shelf).apply { refresh() }
        model.reveal("a")
        model.delete("a")
        assertEquals(listOf("b"), model.state.value.items.map { it.id })
        assertEquals(listOf("b"), shelf.items.map { it.id })
        assertNull(model.state.value.shownValue)
        // A reason Show gave in the deleted item's sheet goes with it.
        model.unlockDismissed("Set a screen lock")
        model.delete("b")
        assertNull(model.state.value.error)
    }

    @Test
    fun `a failure says so in words, and no secret is ever printed`() = runTest {
        shelf.failing = true
        val model = VaultViewModel(shelf)
        model.refresh()
        assertEquals("Anu couldn't open the vault. Try again.", model.state.value.error)
        shelf.failing = false
        model.refresh()
        model.reveal("a")
        assertFalse(model.state.value.toString().contains("secret-a"))
        assertFalse(Revealed.Value("secret-a").toString().contains("secret-a"))
    }

    @Test
    fun `where says the only site a key may go to`() {
        assertEquals("Sent only to generativelanguage.googleapis.com", where(entry("a", "Gemini", listOf("generativelanguage.googleapis.com"))))
        assertEquals("Never sent", where(entry("b", "Note")))
    }

    private companion object {
        fun entry(id: String, label: String, hosts: List<String> = emptyList()) =
            VaultEntry(id, "API key", label, "Test", hosts, listOf("Gemini"), whileUnlocked = true, savedAtMillis = 0, lastUsedAtMillis = null)
    }
}
