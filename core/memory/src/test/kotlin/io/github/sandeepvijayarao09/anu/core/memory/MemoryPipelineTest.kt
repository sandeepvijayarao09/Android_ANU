package io.github.sandeepvijayarao09.anu.core.memory

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MemoryPipelineTest {

    private val store = InMemoryMemoryStore()
    private val brain = ScriptedBrain(BrainId.PRO)
    private var now = Instant.parse("2026-09-25T18:00:00Z")
    private val memory = MemoryPipeline(store, FactExtractor({ brain }, { LocalDate.of(2026, 9, 25) }), clock = { now })

    private fun fact(subject: String, predicate: String, value: String, section: String, quote: String, single: Boolean = false) =
        """{"subject":"$subject","predicate":"$predicate","value":"$value","section":"$section","quote":"$quote","single":$single}"""

    private fun reply(vararg facts: String) = Step.Reply("""{"facts":[${facts.joinToString(",")}]}""")

    private suspend fun learn(message: String, vararg facts: String): Long {
        val event = memory.captureChat(message, "main:${store.allEvents().size}")!!
        brain.enqueue(reply(*facts))
        memory.processPending()
        return event
    }

    @Test
    fun `with search by meaning, facts get vectors, and a message finds a fact it shares no word with`() = runTest {
        val embedder = ConceptEmbedder()
        val meaning = MemoryPipeline(store, FactExtractor({ brain }, { LocalDate.of(2026, 9, 25) }), clock = { now }, embedder = { embedder })
        meaning.captureChat("I'm vegetarian, and my sister Priya lives in Pune", "main:0")
        brain.enqueue(
            reply(
                fact("user", "diet", "vegetarian", "preferences", "I'm vegetarian", single = true),
                fact("user", "sister", "Priya", "people", "my sister Priya"),
            ),
        )
        meaning.processPending()
        // Learning refreshed the profile, which indexed both facts in the user's voice.
        assertEquals(2, store.vectors(embedder.model).size)
        assertEquals(listOf("My diet: vegetarian", "My sister: Priya"), embedder.calls.single().first)
        assertEquals(0, meaning.index())

        val picked = meaning.relevant("Plan a dinner for tonight").map { it.text }
        assertEquals(listOf("diet: vegetarian"), picked)
        val searches = embedder.calls.size
        assertTrue(meaning.relevant("Thanks!", previous = "Plan a dinner for tonight").isEmpty())
        assertEquals("small talk is never embedded", searches, embedder.calls.size)
    }

    @Test
    fun `a message is read on its own, unless it points back to someone the message before names`() = runTest {
        memory.captureChat("My sister Priya lives in Pune, and my manager is Maya Chen", "main:0")
        brain.enqueue(
            reply(
                fact("user", "sister", "Priya", "people", "My sister Priya"),
                fact("Priya", "lives_in", "Pune", "people", "Priya lives in Pune"),
                fact("user", "manager", "Maya Chen", "people", "my manager is Maya Chen"),
            ),
        )
        memory.processPending()
        assertEquals(
            "the last topic doesn't ride along", listOf("manager: Maya Chen"),
            memory.relevant("What does my manager like?", previous = "Where does my sister live?").map { it.text },
        )
        assertEquals(
            "a follow-up finds who it means", listOf("Priya · lives in: Pune", "sister: Priya"),
            memory.relevant("Is she free on Friday?", previous = "Is Priya coming to dinner?").map { it.text },
        )
    }

    @Test
    fun `a new model re-indexes, deleting a fact deletes its vector, and a failure means words only`() = runTest {
        val first = ConceptEmbedder()
        var current: ConceptEmbedder = first
        val meaning = MemoryPipeline(store, FactExtractor({ brain }, { LocalDate.of(2026, 9, 25) }), clock = { now }, embedder = { current })
        meaning.captureChat("I'm vegetarian", "main:0")
        brain.enqueue(reply(fact("user", "diet", "vegetarian", "preferences", "I'm vegetarian", single = true)))
        meaning.processPending()
        current = ConceptEmbedder(model = "concepts@4-v2")
        assertEquals(1, meaning.index())

        current.failing = true
        assertEquals("words still work", listOf("diet: vegetarian"), meaning.relevant("What's my diet?").map { it.text })
        assertEquals("without shared words, nothing", emptyList<String>(), meaning.relevant("Plan a dinner").map { it.text })

        val id = store.activeFacts().single().id
        meaning.delete(id)
        assertTrue(store.vectors("concepts@4-v2").isEmpty())
    }

    @Test
    fun `capture keeps messages about someone, never secrets, and cuts card numbers`() = runTest {
        assertNull(memory.captureChat("Book lunch with Maya tomorrow", "k"))
        assertNull(memory.captureChat("yes please", "k"))
        assertNull(memory.captureChat("My wifi password is hunter22", "k"))
        assertNull(memory.captureChat("My verification code is 482913", "k"))
        val id = memory.captureChat("I'm vegetarian and my card is 4111 1111 1111 1111", "main:3")!!
        val stored = store.allEvents().single { it.id == id }
        assertEquals("I'm vegetarian and my card is [CARD]", stored.content)
        assertEquals(EventSource.CHAT, stored.source)
        assertEquals(EventStatus.PENDING, stored.status)
    }

    @Test
    fun `learning keeps the facts a message states, with provenance, and saves a profile version`() = runTest {
        val event = learn(
            "My manager Maya wants the report by Friday, and I'm vegetarian",
            fact("user", "manager", "Maya", "people", "My manager Maya", single = true),
            fact("I", "Diet", "vegetarian", "preferences", "I'm vegetarian", single = true),
        )
        val request = brain.requests.single()
        assertEquals(listOf(ChatMessage.User("My manager Maya wants the report by Friday, and I'm vegetarian")), request.transcript)
        assertTrue(request.system!!, request.system!!.contains("Today is Friday, 25 September 2026."))
        assertNotNull(request.responseSchema)
        assertEquals(Sampling.Precise, request.sampling)
        assertNull("extraction never carries the memory block", request.memory)

        val facts = store.activeFacts()
        assertEquals(listOf("manager: Maya", "diet: vegetarian"), facts.map { it.text })
        assertTrue(facts.all { it.sources == listOf(event) && it.origin == FactOrigin.INFERRED && it.confidence == 0.6 })
        assertEquals("My manager Maya", facts.first().evidence)
        assertEquals(EventStatus.DONE, store.allEvents().single().status)
        assertEquals(setOf(ProfileSection.PEOPLE, ProfileSection.PREFERENCES), store.latestProfile().map { it.section }.toSet())
        assertEquals("- manager: Maya", store.latestProfile().single { it.section == ProfileSection.PEOPLE }.text)
    }

    @Test
    fun `facts the message doesn't say, and secrets, are dropped`() = runTest {
        learn(
            "I'm vegetarian these days",
            fact("user", "likes", "jazz", "preferences", "I love jazz"),
            fact("user", "diet", "vegetarian", "preferences", "I'm vegetarian"),
            fact("user", "password", "hunter22", "identity", "I'm vegetarian"),
        )
        assertEquals(listOf("diet: vegetarian"), store.activeFacts().map { it.text })
    }

    @Test
    fun `invalid output gets one repair`() = runTest {
        memory.captureChat("I live in Seattle now", "main:0")
        brain.enqueue(Step.Reply("Sure! Here are the facts."), reply(fact("user", "lives_in", "Seattle", "places", "I live in Seattle", single = true)))
        memory.processPending()
        assertEquals(listOf("lives in: Seattle"), store.activeFacts().map { it.text })
        val repair = brain.requests[1].transcript
        assertEquals(3, repair.size)
        assertEquals(ChatMessage.Model("Sure! Here are the facts."), repair[1])
    }

    @Test
    fun `a failed event waits for the next run, and is dropped after three`() = runTest {
        memory.captureChat("I live in Seattle now", "main:0")
        repeat(3) { run ->
            brain.enqueue(Step.Reply("nope"), Step.Reply("still nope"))
            val result = memory.processPending()
            assertEquals(1, result.failed)
            val event = store.allEvents().single()
            assertEquals(run + 1, event.attempts)
            assertEquals(if (run < 2) EventStatus.PENDING else EventStatus.FAILED, event.status)
        }
        assertEquals(6, brain.requests.size) // never retried twice in one run
    }

    @Test
    fun `without an on-device model nothing is learned and nothing is lost`() = runTest {
        val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
        for (unavailable in listOf<() -> ScriptedBrain?>({ null }, { cloud })) {
            val pipeline = MemoryPipeline(store, FactExtractor(unavailable), clock = { now })
            pipeline.captureChat("I'm vegetarian", "main:0")
            assertTrue(pipeline.processPending().noModel)
        }
        assertTrue("memory is never extracted in the cloud", cloud.requests.isEmpty())
        assertTrue(store.allEvents().all { it.status == EventStatus.PENDING && it.attempts == 0 })
    }

    @Test
    fun `a repeated fact gains confidence and a source, and a new value replaces a one-at-a-time relation`() = runTest {
        val first = learn("My manager is Maya", fact("user", "manager", "Maya", "people", "My manager is Maya", single = true))
        val second = learn("As I said, my manager is Maya", fact("user", "manager", "maya", "people", "my manager is Maya", single = true))
        val maya = store.activeFacts().single()
        assertEquals(listOf(first, second), maya.sources)
        assertEquals(0.75, maya.confidence, 0.001)

        learn("My new manager is Raj", fact("user", "manager", "Raj", "people", "My new manager is Raj"))
        val raj = store.activeFacts().single()
        assertEquals("manager: Raj", raj.text)
        val old = store.allFacts().single { it.value == "Maya" }
        assertEquals(FactStatus.SUPERSEDED, old.status)
        assertEquals(raj.id, old.supersededBy)
    }

    @Test
    fun `an inferred fact never overrides what the user said`() = runTest {
        memory.remember("user", "manager", "Maya Chen", ProfileSection.PEOPLE, sensitive = false, ref = "main:0")
        learn("I think my manager is Raj", fact("user", "manager", "Raj", "people", "my manager is Raj", single = true))
        val manager = store.activeFacts().single()
        assertEquals("manager: Maya Chen", manager.text)
        assertEquals(FactOrigin.USER_STATED, manager.origin)
        assertEquals(1.0, manager.confidence, 0.0)
        assertEquals(EventSource.STATEMENT, store.allEvents().first().source)
    }

    @Test
    fun `a deleted fact is not learned again, and only its fingerprint is kept`() = runTest {
        learn("I'm vegetarian", fact("user", "diet", "vegetarian", "preferences", "I'm vegetarian"))
        memory.delete(store.activeFacts().single().id)
        assertTrue(store.allFacts().isEmpty())
        learn("I'm vegetarian, remember", fact("user", "diet", "Vegetarian", "preferences", "I'm vegetarian"))
        assertTrue(store.activeFacts().isEmpty())
        assertTrue(store.latestProfile().all { it.text.isEmpty() })
    }

    @Test
    fun `forgetting a source removes what came only from it and restores what it replaced (I4, I5)`() = runTest {
        learn("I live in Seattle", fact("user", "lives_in", "Seattle", "places", "I live in Seattle", single = true))
        val moved = learn(
            "I moved to Boston and I work at Acme",
            fact("user", "lives_in", "Boston", "places", "I moved to Boston", single = true),
            fact("user", "works_at", "Acme", "work", "I work at Acme", single = true),
        )
        val confirmed = learn("I still work at Acme", fact("user", "works_at", "Acme", "work", "I still work at Acme"))

        memory.forgetSource(moved)

        assertEquals(setOf("lives in: Seattle", "works at: Acme"), store.activeFacts().map { it.text }.toSet())
        assertEquals(listOf(confirmed), store.activeFacts().single { it.predicate == "works_at" }.sources)
        assertTrue(store.allFacts().none { it.value == "Boston" })
        assertTrue(store.allEvents().none { it.id == moved })
        assertTrue("every fact keeps a source (I4)", store.allFacts().all { it.sources.isNotEmpty() })
        assertEquals("- lives in: Seattle", store.latestProfile().single { it.section == ProfileSection.PLACES }.text)
    }

    @Test
    fun `confirming makes a fact the user's own, and correcting replaces it for good`() = runTest {
        learn("My manager is Maya", fact("user", "manager", "Maya", "people", "My manager is Maya", single = true))
        val maya = store.activeFacts().single()
        memory.confirm(maya.id)
        with(store.activeFacts().single()) {
            assertEquals(FactOrigin.USER_STATED, origin)
            assertEquals(1.0, confidence, 0.0)
            assertEquals(2, sources.size)
        }

        memory.correct(maya.id, "Maya Chen")
        val corrected = store.activeFacts().single()
        assertEquals("manager: Maya Chen", corrected.text)
        assertEquals(FactOrigin.USER_STATED, corrected.origin)
        learn("My manager is Maya", fact("user", "manager", "Maya", "people", "My manager is Maya", single = true))
        assertEquals("the old value isn't learned again", listOf("manager: Maya Chen"), store.activeFacts().map { it.text })
    }

    @Test
    fun `the memory block puts the user's own facts first and stays within budget`() = runTest {
        assertNull(memory.memoryBlock())
        learn("My sister Priya lives in Pune", fact("Priya", "lives_in", "Pune", "people", "My sister Priya lives in Pune"))
        memory.remember("user", "prefers_meeting_time", "mornings", ProfileSection.PREFERENCES, false, null)
        val block = memory.memoryBlock()!!
        assertTrue(block, block.startsWith("What you know about the user, from their own words and their edits in the Memory tab."))
        assertTrue(block, block.contains("when it disagrees with older messages in this chat, trust it"))
        assertTrue(block, block.contains("- Preferences: prefers meeting time: mornings"))
        assertTrue(block, block.contains("- People and relationships: Priya · lives in: Pune"))
        // Within a tight budget the user's own fact is kept and the inferred one is left out.
        val tight = memory.memoryBlock(budgetChars = 260)!!
        assertTrue(tight, tight.contains("mornings") && !tight.contains("Pune"))
    }

    @Test
    fun `only one learning run goes at a time, and a run stops when asked`() = runTest {
        memory.captureChat("I'm vegetarian", "main:0")
        memory.captureChat("My manager is Maya", "main:1")
        brain.enqueue(Step.Hang())
        val first = backgroundScope.launch { memory.processPending() }
        runCurrent()
        assertTrue(memory.processPending().busy)
        first.cancel()
        runCurrent()

        brain.enqueue(reply(fact("user", "diet", "vegetarian", "preferences", "I'm vegetarian")))
        val result = memory.processPending { store.allEvents().none { it.status == EventStatus.DONE } }
        assertEquals(1, result.processed)
        assertEquals(1, store.allEvents().count { it.status == EventStatus.PENDING })
    }

    @Test
    fun `what the user types in the Memory tab is kept as their own word`() = runTest {
        brain.enqueue(reply(fact("user", "diet", "vegetarian", "preferences", "I'm vegetarian", single = true)))
        assertEquals(1, memory.add("  I'm vegetarian  "))
        val kept = store.activeFacts().single()
        assertEquals("diet: vegetarian", kept.text)
        assertEquals(FactOrigin.USER_STATED, kept.origin)
        assertEquals(1.0, kept.confidence, 0.0)
        val source = store.allEvents().single()
        assertEquals(EventSource.STATEMENT, source.source)
        assertEquals("I'm vegetarian", source.content)
        assertEquals(EventStatus.DONE, source.status)
        assertEquals(listOf(source.id), kept.sources)
    }

    @Test
    fun `typed secrets are refused before any model sees them, and nothing found keeps nothing`() = runTest {
        val refused = runCatching { memory.add("my wifi password is hunter22") }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertTrue(brain.requests.isEmpty())
        brain.enqueue(reply())
        assertEquals(0, memory.add("hello there"))
        assertTrue(store.allEvents().isEmpty())
    }
}
