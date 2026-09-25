package io.github.sandeepvijayarao09.anu.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

class BrainRequestTest {

    @Test
    fun `transcript must end with a user or tool turn`() {
        assertThrows(IllegalArgumentException::class.java) { BrainRequest(transcript = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            BrainRequest(transcript = listOf(ChatMessage.User("hi"), ChatMessage.Model("hello")))
        }
        BrainRequest(transcript = listOf(ChatMessage.User("hi")))
    }
}
