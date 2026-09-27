package io.github.sandeepvijayarao09.anu.feature.memory

import io.github.sandeepvijayarao09.anu.core.memory.Fact
import io.github.sandeepvijayarao09.anu.core.memory.FactOrigin
import io.github.sandeepvijayarao09.anu.core.memory.FactStatus
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import io.github.sandeepvijayarao09.anu.core.memory.USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FactWordsTest {

    private fun fact(
        subject: String,
        predicate: String,
        value: String,
        origin: FactOrigin = FactOrigin.USER_STATED,
        sources: List<Long> = listOf(1),
        sensitive: Boolean = false,
        section: ProfileSection = ProfileSection.PEOPLE,
    ) = Fact(
        id = 1, subject = subject, predicate = predicate, value = value, section = section, confidence = 1.0,
        origin = origin, status = FactStatus.ACTIVE, sensitive = sensitive, evidence = null,
        firstSeen = Instant.EPOCH, lastConfirmed = Instant.EPOCH, sources = sources,
    )

    private val date: (Instant) -> String = { "Sep 24" }

    @Test
    fun `a noun relation reads as the value, with how it relates to you`() {
        assertEquals(FactWords("Maya Chen", "Your manager"), factWords(USER, "manager", "Maya Chen"))
        assertEquals(FactWords("Vegetarian", "Your diet"), factWords(USER, "diet", "vegetarian"))
        assertEquals(FactWords("12 March", "Maya Chen's birthday"), factWords("Maya Chen", "birthday", "12 March"))
        assertEquals(FactWords("Boston", "Your home city"), factWords(USER, "home_city", "Boston"))
    }

    @Test
    fun `a verb relation reads as a sentence`() {
        assertEquals(FactWords("Prefers meetings in the morning", null), factWords(USER, "prefers", "meetings in the morning"))
        assertEquals(FactWords("Priya lives in Pune", null), factWords("Priya", "lives_in", "Pune"))
        assertEquals(FactWords("Reports to Maya Chen", null), factWords(USER, "reports_to", "Maya Chen"))
        assertEquals(FactWords("Allergic to peanuts", null), factWords(USER, "allergic_to", "peanuts"))
    }

    @Test
    fun `a value that repeats the verb is the sentence, and a long verb relation labels the value`() {
        assertEquals(
            FactWords("Prefers meetings in the morning", null),
            factWords(USER, "prefers_meeting_time", "prefers meetings in the morning"),
        )
        assertEquals(FactWords("Mornings", "Prefers meeting time"), factWords(USER, "prefers_meeting_time", "mornings"))
        assertEquals(FactWords("Maya likes sushi", null), factWords("Maya", "likes", "sushi"))
    }

    @Test
    fun `yes and no values don't read as true or false`() {
        assertEquals(FactWords("Vegetarian", null), factWords(USER, "is_vegetarian", "true"))
        assertEquals(FactWords("Priya has a dog", null), factWords("Priya", "has_a_dog", "yes"))
        assertEquals(FactWords("Eats meat: false", null), factWords(USER, "eats_meat", "false"))
    }

    @Test
    fun `sentence case leaves brand names alone`() {
        assertEquals("Vegetarian", sentenceCase("vegetarian"))
        assertEquals("iPhone", sentenceCase("iPhone"))
        assertEquals("eBay", sentenceCase("eBay"))
        assertEquals("Maya", sentenceCase("Maya"))
        assertEquals("", sentenceCase("  "))
    }

    @Test
    fun `the row says where a fact came from only when the right side doesn't`() {
        val manager = fact(USER, "manager", "Maya Chen")
        assertEquals("Your manager", rowSubtitle(manager, factWords(manager), date))

        val vegetarian = fact(USER, "is_vegetarian", "true")
        assertEquals("You said · Sep 24", rowSubtitle(vegetarian, factWords(vegetarian), date))

        val guess = fact(USER, "prefers", "meetings in the morning", FactOrigin.INFERRED, sources = listOf(1, 2))
        assertEquals("Learned from 2 messages", rowSubtitle(guess, factWords(guess), date))

        val guessedManager = fact(USER, "manager", "Maya Chen", FactOrigin.INFERRED)
        assertEquals("Your manager · Learned from 1 message", rowSubtitle(guessedManager, factWords(guessedManager), date))

        val health = fact(USER, "allergy", "penicillin", sensitive = true)
        assertEquals("Your allergy · Sensitive", rowSubtitle(health, factWords(health), date))
    }

    @Test
    fun `search matches every word anywhere in how a fact reads`() {
        val manager = fact(USER, "manager", "Maya Chen")
        assertTrue(matches(manager, ""))
        assertTrue(matches(manager, "maya"))
        assertTrue(matches(manager, "your manager"))
        assertTrue(matches(manager, "people"))
        assertFalse(matches(manager, "maya sister"))
    }
}
