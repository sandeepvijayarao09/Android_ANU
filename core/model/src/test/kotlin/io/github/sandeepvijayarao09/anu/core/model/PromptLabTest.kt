package io.github.sandeepvijayarao09.anu.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLabTest {

    @Test
    fun `a lab request carries only its instruction and prompt, with no memory, facts, tools or history`() {
        val request = labRequest("  Be brief.  ", " What is a lighthouse? ", Sampling.Default, maxOutputTokens = 256, thinking = true)
        assertEquals("Be brief.", request.system)
        assertEquals(listOf(ChatMessage.User("What is a lighthouse?")), request.transcript)
        assertTrue(request.tools.isEmpty())
        assertNull(request.memory)
        assertNull(request.relevant)
        assertTrue(request.sharedFacts.isEmpty())
        assertEquals(256, request.maxOutputTokens)
        assertEquals("lab", request.purpose)
        assertNull("a blank instruction is none", labRequest(" ", "Hi", Sampling.Default, 64, false).system)
    }
}
