package io.github.sandeepvijayarao09.anu.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactPhrasingTest {

    private fun you(predicate: String, value: String) = factSentence(USER, predicate, value)

    @Test
    fun `a value that says the relation's verb again is the sentence, said once`() {
        // What E4B kept on the emulator for "Remember that I buy oat milk every week".
        assertEquals("You buy oat milk every week", you("buys_at", "buy oat milk every week"))
        assertEquals("You prefer meetings in the morning", you("prefers_meeting_time", "prefers meetings in the morning"))
        assertEquals("You usually buy oat milk on Sundays", you("usually_buys", "buys oat milk on Sundays"))
        assertEquals("You are allergic to peanuts", you("allergic_to", "Allergic to peanuts"))
        assertEquals("Raj buys oat milk every week", factSentence("Raj", "buys_at", "buy oat milk every week"))
    }

    @Test
    fun `a verb relation reads as a sentence to you, and of anyone else`() {
        assertEquals("You live in Pune", you("lives_in", "Pune"))
        assertEquals("You like sushi", you("likes", "sushi"))
        assertEquals("You like sushi", you("like", "sushi"))
        assertEquals("You work at Google", you("works_at", "Google"))
        assertEquals("You report to Maya Chen", you("reports_to", "Maya Chen"))
        assertEquals("You watch football", you("watches", "football"))
        assertEquals("You study piano", you("studies", "piano"))
        assertEquals("You go to the gym at 7", you("goes_to", "the gym at 7"))
        assertEquals("You want to visit Japan", you("wants_to_visit", "Japan"))
        assertEquals("You are a nurse", you("is_a", "nurse"))
        assertEquals("You were born in Pune", you("born_in", "Pune"))
        assertEquals("You moved to Boston", you("moved_to", "Boston"))
        assertEquals("You never eat pork", you("never_eats", "pork"))
        assertEquals("You don't eat pork", you("doesnt_eat", "pork"))
        assertEquals("You prefer meeting time: mornings", you("prefers_meeting_time", "mornings"))
        assertEquals("Priya lives in Pune", factSentence("Priya", "lives_in", "Pune"))
        assertEquals("Priya is allergic to peanuts", factSentence("Priya", "allergic_to", "peanuts"))
        assertEquals("Maya likes sushi", factSentence("Maya", "like", "sushi"))
    }

    @Test
    fun `a noun relation says whose it is`() {
        assertEquals("Your manager is Maya Chen", you("manager", "Maya Chen"))
        assertEquals("Your diet is vegetarian", you("diet", "vegetarian"))
        assertEquals("Your kids are Anna and Ben", you("kids", "Anna and Ben"))
        assertEquals("Your children are Anna and Ben", you("children", "Anna and Ben"))
        assertEquals("Raj's birthday is 12 March", factSentence("Raj", "birthday", "12 March"))
        // A plain verb often starts a noun, and "plans" alone is one.
        assertEquals("Your work address is 12 Main St", you("work_address", "12 Main St"))
        assertEquals("Your travel plans are Japan in May", you("travel_plans", "Japan in May"))
        assertEquals("Your plans are Japan in May", you("plans", "Japan in May"))
        // "Your weekly shop is buy oat milk" isn't English.
        assertEquals("Your weekly shop: buy oat milk", you("weekly_shop", "buy oat milk"))
    }

    @Test
    fun `yes and no read as what they say, never as true or false`() {
        assertEquals("You are vegetarian", you("is_vegetarian", "true"))
        assertEquals("You are vegetarian", you("vegetarian", "yes"))
        assertEquals("You have a dog", you("has_a_dog", "yes"))
        assertEquals("You don't eat meat", you("eats_meat", "false"))
        assertEquals("You aren't vegan", you("is_vegan", "no"))
        assertEquals("You can't drive", you("can_drive", "false"))
        assertEquals("Priya has a dog", factSentence("Priya", "has_a_dog", "yes"))
        assertEquals("Priya doesn't eat meat", factSentence("Priya", "eats_meat", "false"))
    }

    @Test
    fun `what can't be said plainly stays a label, with nothing left out`() {
        assertEquals("Smoker: no", you("smoker", "false"))
        assertEquals("Member of: the choir", you("member_of", "the choir"))
        assertEquals("Lives in: no", you("lives_in", "no"))
        assertEquals("no double negative", "Does not smoke: no", you("does_not_smoke", "false"))
        assertEquals("Priya · smoker: no", factSentence("Priya", "smoker", "no"))
    }

    @Test
    fun `the value is kept word for word, and nothing is ever dropped silently`() {
        val facts = listOf(
            Triple(USER, "likes", "Trader Joe's"),
            Triple(USER, "owns", "an iPhone 17"),
            Triple(USER, "coffee_order", "oat flat white, extra hot"),
            Triple("Maya Chen", "role", "design lead"),
            Triple(USER, "prefers_meeting_time", "mornings"),
            Triple(USER, "member_of", "the choir"),
        )
        facts.forEach { (subject, predicate, value) ->
            val sentence = factSentence(subject, predicate, value)
            assertTrue(sentence, value in sentence)
            assertFalse(sentence, '_' in sentence)
        }
    }

    @Test
    fun `inside a summary a sentence follows "that", and a label is quoted`() {
        assertEquals("that you buy oat milk every week", factClause(USER, "buys_at", "buy oat milk every week"))
        assertEquals("that Raj's birthday is 12 March", factClause("Raj", "birthday", "12 March"))
        assertEquals("your weekly shop: buy oat milk", factClause(USER, "weekly_shop", "buy oat milk"))
        assertEquals("\"Smoker: no\"", factClause(USER, "smoker", "no"))
    }

    @Test
    fun `verbs agree with who they're about`() {
        assertEquals("buys", FactGrammar.thirdPerson("buy"))
        assertEquals("buys", FactGrammar.thirdPerson("buys"))
        assertEquals("watches", FactGrammar.thirdPerson("watch"))
        assertEquals("studies", FactGrammar.thirdPerson("study"))
        assertEquals("has", FactGrammar.thirdPerson("have"))
        assertEquals("moved", FactGrammar.thirdPerson("moved"))
        assertEquals("allergic", FactGrammar.thirdPerson("allergic"))
        assertTrue(FactGrammar.sameVerb("buys", "buy"))
        assertTrue(FactGrammar.sameVerb("has", "have"))
        assertFalse(FactGrammar.sameVerb("likes", "lives"))
    }
}
