package io.github.sandeepvijayarao09.anu.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointingWordsTest {

    @Test
    fun `questions that point at the screen use it`() {
        for (question in listOf("What's this?", "what is this", "Summarize this page", "Reply to this", "What's on my screen?", "Translate this", "what am I looking at", "Is this email a scam?", "Draft a reply", "respond to her")) {
            assertTrue(question, PointingWords.asks(question))
        }
    }

    @Test
    fun `other questions don't`() {
        for (question in listOf("What's on my calendar today?", "Set a timer for 10 minutes", "What is the capital of France?", "Text Maya I'm late", "This weekend, remind me to call Priya")) {
            assertFalse(question, PointingWords.asks(question))
        }
    }
}
