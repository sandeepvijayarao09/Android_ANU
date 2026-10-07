package io.github.sandeepvijayarao09.anu.connector.contacts

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddContactToolTest {
    private val saved = mutableListOf<Triple<String, String?, String?>>()

    /** Knows Maya Chen already; anyone else is new. */
    private val tool = AddContactTool { name, phone, email ->
        saved += Triple(name, phone, email)
        if (name.equals("maya chen", ignoreCase = true)) ContactWriter.Saved("Maya Chen", created = false) else ContactWriter.Saved(name, created = true)
    }
    private val ctx = ToolContext("run", "key")

    @Test
    fun `a new person is saved, and someone saved gets the detail added`() = runTest {
        assertEquals("Saved Raj Patel in your contacts.", tool.invoke(AddContactInput("Raj Patel", phone = "555 0100"), ctx).said)
        assertEquals("Added that to Maya Chen in your contacts.", tool.invoke(AddContactInput("maya chen", email = "maya@example.com"), ctx).said)
        assertEquals(Triple("Raj Patel", "555 0100", null), saved.first())
    }

    @Test
    fun `the sheet says exactly what is saved, and the name can't be edited`() {
        assertEquals(
            "Save Raj Patel in your contacts, with phone +1 555 0100 and email raj@example.com",
            tool.describe(AddContactInput("Raj Patel", "+1 555 0100", "raj@example.com")),
        )
        assertEquals(RiskTier.WRITE_PRIVATE, tool.risk(AddContactInput("Raj", "5550100")))
        assertEquals(listOf("phone", "email"), tool.editable.map { it.key })
    }

    @Test
    fun `nothing to save, or a detail that isn't one, is invalid`() {
        assertThrows(IllegalArgumentException::class.java) { tool.describe(AddContactInput("Raj Patel")) }
        assertThrows(IllegalArgumentException::class.java) { AddContactInput("Raj", phone = "call me") }
        assertThrows(IllegalArgumentException::class.java) { AddContactInput("Raj", email = "raj at example") }
    }
}
