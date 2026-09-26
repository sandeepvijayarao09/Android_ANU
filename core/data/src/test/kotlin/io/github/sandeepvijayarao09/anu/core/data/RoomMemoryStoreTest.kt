package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sandeepvijayarao09.anu.core.memory.EventStatus
import io.github.sandeepvijayarao09.anu.core.memory.FactExtractor
import io.github.sandeepvijayarao09.anu.core.memory.FactOrigin
import io.github.sandeepvijayarao09.anu.core.memory.FactStatus
import io.github.sandeepvijayarao09.anu.core.memory.MemoryPipeline
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** The memory pipeline against Room's real SQL (§8), so provenance and cascades hold in the database too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMemoryStoreTest {

    private lateinit var db: AnuDatabase
    private lateinit var store: RoomMemoryStore
    private lateinit var memory: MemoryPipeline
    private val brain = ScriptedBrain(BrainId.PRO)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AnuDatabase::class.java).build()
        store = RoomMemoryStore(db)
        memory = MemoryPipeline(store, FactExtractor({ brain }, { LocalDate.of(2026, 9, 25) }), clock = { Instant.parse("2026-09-25T18:00:00Z") })
    }

    @After
    fun tearDown() = db.close()

    private fun fact(predicate: String, value: String, section: String, quote: String) =
        """{"subject":"user","predicate":"$predicate","value":"$value","section":"$section","quote":"$quote","single":true}"""

    private suspend fun learn(message: String, vararg facts: String): Long {
        val id = memory.captureChat(message, "main:1")!!
        brain.enqueue(Step.Reply("""{"facts":[${facts.joinToString(",")}]}"""))
        memory.processPending()
        return id
    }

    @Test
    fun `facts keep their sources, and forgetting a source cascades in the database (I4, I5)`() = runBlocking {
        learn("I live in Seattle", fact("lives_in", "Seattle", "places", "I live in Seattle"))
        val moved = learn(
            "I moved to Boston and I work at Acme",
            fact("lives_in", "Boston", "places", "I moved to Boston"),
            fact("works_at", "Acme", "work", "I work at Acme"),
        )
        val again = learn("I still work at Acme", fact("works_at", "Acme", "work", "I still work at Acme"))
        assertEquals(setOf("lives in: Boston", "works at: Acme"), store.activeFacts().map { it.text }.toSet())
        assertEquals(listOf(moved, again), store.activeFacts().single { it.predicate == "works_at" }.sources)

        memory.forgetSource(moved)

        val facts = store.activeFacts()
        assertEquals(setOf("lives in: Seattle", "works at: Acme"), facts.map { it.text }.toSet())
        assertEquals(listOf(again), facts.single { it.predicate == "works_at" }.sources)
        assertTrue(facts.all { it.sources.isNotEmpty() && it.status == FactStatus.ACTIVE })
        assertTrue(store.events(listOf(moved)).isEmpty())
        assertEquals(0, db.memory().factsFrom(moved).size)
    }

    @Test
    fun `the user's controls, profile versions and live updates work on the database`() = runBlocking {
        learn("My manager is Maya", fact("manager", "Maya", "people", "My manager is Maya"))
        assertEquals(0, store.observePendingCount().first())
        val maya = store.observeFacts().first().single()

        memory.correct(maya.id, "Maya Chen")
        val corrected = store.activeFacts().single()
        assertEquals("manager: Maya Chen", corrected.text)
        assertEquals(FactOrigin.USER_STATED, corrected.origin)

        memory.remember("user", "diet", "vegetarian", ProfileSection.PREFERENCES, sensitive = false, ref = "main:3")
        val diet = store.activeFacts().single { it.predicate == "diet" }
        memory.delete(diet.id)
        learn("I'm vegetarian", fact("diet", "vegetarian", "preferences", "I'm vegetarian"))
        assertEquals("a deleted fact isn't learned again", listOf("manager: Maya Chen"), store.activeFacts().map { it.text })

        val people = store.latestProfile().single { it.section == ProfileSection.PEOPLE }
        assertEquals("- manager: Maya Chen", people.text)
        assertEquals(2, people.version)
        assertEquals(listOf(corrected.id), people.factIds)
        assertTrue(store.pending(10).isEmpty())
        assertEquals(EventStatus.DONE, store.events(listOf(1L)).single().status)
    }
}
