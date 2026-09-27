package io.github.sandeepvijayarao09.anu.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RelevanceTest {

    private var nextId = 1L

    private fun fact(subject: String, predicate: String, value: String, origin: FactOrigin = FactOrigin.USER_STATED, confidence: Double = 1.0) = Fact(
        id = nextId++, subject = subject, predicate = predicate, value = value, section = ProfileSection.PEOPLE,
        confidence = confidence, origin = origin, status = FactStatus.ACTIVE, sensitive = false, evidence = null,
        firstSeen = Instant.EPOCH, lastConfirmed = Instant.EPOCH, sources = listOf(1),
    )

    private val manager = fact(USER, "manager", "Maya Chen")
    private val birthday = fact("Maya Chen", "birthday", "12 March")
    private val sister = fact(USER, "sister", "Priya")
    private val lives = fact("Priya", "lives_in", "Pune")
    private val diet = fact(USER, "diet", "vegetarian")
    private val city = fact(USER, "home_city", "Boston")
    private val all = listOf(city, diet, lives, sister, birthday, manager)

    private fun pick(message: String, facts: List<Fact> = all) = Relevance.pick(message, facts).map { it.text }

    @Test
    fun `a person named in the message brings their facts, and how they relate to the user`() {
        val picked = pick("What should I get Maya?")
        assertEquals(listOf(birthday.text, manager.text), picked)
    }

    @Test
    fun `the user's relations and topics match by their words`() {
        assertEquals(listOf(sister.text), pick("Call my sister"))
        assertEquals(listOf(diet.text), pick("Suggest a dinner for my diet"))
        assertEquals(listOf(birthday.text), pick("Any birthdays coming up?"))
        assertEquals(listOf(lives.text, sister.text), pick("Is Priya's flight on time?"))
    }

    @Test
    fun `small talk and function words bring nothing`() {
        assertTrue(pick("Hi, how are you?").isEmpty())
        assertTrue(pick("What can you do for me?").isEmpty())
        assertTrue(pick("").isEmpty())
    }

    @Test
    fun `stronger matches come first, then the user's own facts, within the limit and budget`() {
        val guess = fact("Maya Chen", "favorite_food", "sushi", origin = FactOrigin.INFERRED, confidence = 0.6)
        val picked = Relevance.pick("Lunch with Maya Chen", listOf(guess, manager, birthday), limit = 2).map { it.text }
        assertEquals("Maya Chen as the subject beats Maya Chen as a value; the user's own beats a guess", listOf(birthday.text, guess.text), picked)
        val tight = Relevance.pick("Maya", listOf(manager, birthday), budgetChars = manager.text.length + 3)
        assertEquals(1, tight.size)
    }
}
