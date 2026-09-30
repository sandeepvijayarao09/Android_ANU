package io.github.sandeepvijayarao09.anu.connector.contacts

import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FindContactToolTest {
    private val maya = Contact("Maya Chen", listOf("maya.chen@example.com"), emptyList())
    private val raj = Contact("Raj Patel", listOf("raj@example.com"), listOf("555-0100"))

    /** Like Android's filter: each word asked for starts a word of the name. */
    private val book = object : ContactSource {
        override suspend fun find(name: String, limit: Int) = listOf(maya, raj).filter { contact ->
            val parts = contact.name.lowercase().split(" ")
            name.lowercase().split(" ").all { word -> parts.any { it.startsWith(word) } }
        }.take(limit)

        override suspend fun names() = listOf(maya.name, raj.name)
    }
    private val tool = FindContactTool(book)
    private val ctx = ToolContext("run", "key")

    @Test
    fun `a name that matches is found as it is`() = runTest {
        val out = tool.invoke(FindContactInput("Maya"), ctx)
        assertEquals(listOf("Maya Chen"), out.matches.map { it.name })
        assertNull(out.note)
    }

    @Test
    fun `a name heard a little differently finds the close one, and says so`() = runTest {
        val out = tool.invoke(FindContactInput("Mya Chan"), ctx)
        assertEquals(listOf("maya.chen@example.com"), out.matches.single().emails)
        assertEquals(true, out.note?.startsWith("No one has exactly that name."))
        assertEquals(listOf("Raj Patel"), tool.invoke(FindContactInput("Raj Patle"), ctx).matches.map { it.name })
    }

    @Test
    fun `no one close is no one`() = runTest {
        val out = tool.invoke(FindContactInput("Priya"), ctx)
        assertEquals(emptyList<ContactOut>(), out.matches)
        assertNull(out.note)
    }

    @Test
    fun `closeness allows about a third of each word, and short words must match`() {
        val names = listOf("Maya Chen", "Mia Chen", "Al Stone", "Ali Stone")
        assertEquals(listOf("Maya Chen", "Mia Chen"), closeNames("Maia Chen", names, 5))
        assertEquals(listOf("Al Stone"), closeNames("Al", names, 5))
        assertEquals(emptyList<String>(), closeNames("Mo", names, 5))
        assertEquals(emptyList<String>(), closeNames("Christopher", names, 5))
    }
}
