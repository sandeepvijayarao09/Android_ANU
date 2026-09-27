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
        assertTrue("a greeting isn't about mornings", pick("Good morning", all + fact(USER, "meetings", "mornings")).isEmpty())
        assertTrue("a small number isn't a date", pick("What is 12 times 7?").isEmpty())
        assertEquals(listOf(birthday.text), pick("Anything on 12 March?"))
    }

    @Test
    fun `a fact close in meaning counts without a shared word, and a distant one doesn't`() {
        val similarity = mapOf(diet.id to 0.62f, city.id to 0.20f)
        assertEquals(listOf(diet.text), Relevance.pick("Plan tonight's meal", all, similarity).map { it.text })
        assertTrue("small talk stays empty when nothing is close", Relevance.pick("Hi, how are you?", all, mapOf(diet.id to 0.3f)).isEmpty())
    }

    @Test
    fun `closeness adds to word matches, so a close fact overtakes a stronger word match`() {
        // By words alone "Priya lives in Pune" wins (Priya is its subject); the sister fact is much closer in meaning.
        assertEquals(listOf(lives.text, sister.text), pick("Is Priya's flight on time?"))
        val picked = Relevance.pick("Is Priya's flight on time?", all, mapOf(sister.id to 0.9f)).map { it.text }
        assertEquals(listOf(sister.text, lives.text), picked)
    }

    @Test
    fun `once there are enough facts, a close one must also stand out from the rest`() {
        // Everything is about as close: nothing stands out, so meaning adds nothing (S3: some facts are close to everything).
        val flat = all.associate { it.id to 0.40f }
        assertTrue(Relevance.pick("Plan tonight's meal", all, flat).isEmpty())
        // The diet stands out from the others.
        val peaked = all.associate { it.id to 0.30f } + (diet.id to 0.42f)
        assertEquals(listOf(diet.text), Relevance.pick("Plan tonight's meal", all, peaked).map { it.text })
        assertTrue("small talk is never searched", !Relevance.hasContent("Thanks!") && Relevance.hasContent("Plan tonight's meal"))
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
