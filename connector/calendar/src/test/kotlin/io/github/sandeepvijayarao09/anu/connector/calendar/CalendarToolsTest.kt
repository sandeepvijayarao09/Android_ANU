package io.github.sandeepvijayarao09.anu.connector.calendar

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.agent.ToolOutcome
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.RunState
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarToolsTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String): Instant = ZonedDateTime.parse("${text}-04:00[America/New_York]").toInstant()

    private class FakeCalendar(val existing: MutableList<CalendarEvent> = mutableListOf()) : CalendarSource {
        val inserted = mutableListOf<NewEvent>()
        override suspend fun events(from: Instant, to: Instant) = existing.filter { it.end > from && it.start < to }
        override suspend fun insert(event: NewEvent): Long {
            inserted += event
            return 100L + inserted.size
        }
    }

    private val ctx = ToolContext("run", "key")

    @Test
    fun `free slots skip busy time, keep to working hours, and round to the quarter hour`() {
        val busy = listOf(at("2026-09-28T09:00:00")..at("2026-09-28T10:10:00"), at("2026-09-28T10:00:00")..at("2026-09-28T12:00:00"))
        val slots = FreeSlots.find(
            busy, LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-28"), Duration.ofMinutes(60),
            LocalTime.of(9, 0), LocalTime.of(14, 0), zone, now = at("2026-09-25T08:00:00"),
        )
        assertEquals(listOf(at("2026-09-28T12:00:00")..at("2026-09-28T13:00:00"), at("2026-09-28T13:00:00")..at("2026-09-28T14:00:00")).take(1), slots.take(1))
        assertTrue(slots.all { it.start >= at("2026-09-28T12:00:00") && it.endInclusive <= at("2026-09-28T14:00:00") })
    }

    @Test
    fun `free slots never start in the past`() {
        val slots = FreeSlots.find(
            emptyList(), LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-25"), Duration.ofMinutes(30),
            LocalTime.of(9, 0), LocalTime.of(18, 0), zone, now = at("2026-09-25T10:31:00"),
        )
        assertEquals(at("2026-09-25T10:45:00"), slots.first().start)
    }

    @Test
    fun `events are listed in local time`() = runTest {
        val calendar = FakeCalendar(mutableListOf(CalendarEvent(1, "Standup", at("2026-09-28T09:30:00"), at("2026-09-28T09:45:00"), false, null, listOf("raj@example.com"))))
        val out = ListEventsTool(calendar) { zone }.invoke(EventsInput("2026-09-28", "2026-09-29"), ctx)
        assertEquals(listOf(EventOut("Standup", "2026-09-28T09:30", "2026-09-28T09:45", false, null, listOf("raj@example.com"))), out.events)
    }

    @Test
    fun `creating an event is private without attendees and external with them`() {
        val tool = CreateEventTool(FakeCalendar()) { zone }
        assertEquals(RiskTier.WRITE_PRIVATE, tool.risk(CreateEventInput("Dentist", "2026-09-28T15:00", "2026-09-28T16:00")))
        val invite = CreateEventInput("Design review", "2026-09-28T14:00", "2026-09-28T15:00", attendees = listOf("maya@example.com"))
        assertEquals(RiskTier.WRITE_EXTERNAL, tool.risk(invite))
        assertEquals("Add \"Design review\" on Mon 28 Sep, 14:00–15:00 and invite maya@example.com", tool.describe(invite))
    }

    @Test
    fun `the event is stored with the parsed times and zone`() = runTest {
        val calendar = FakeCalendar()
        val out = CreateEventTool(calendar) { zone }.invoke(CreateEventInput("Dentist", "2026-09-28T15:00", "2026-09-28T16:00", "Main St"), ctx)
        assertEquals(NewEvent("Dentist", at("2026-09-28T15:00:00"), at("2026-09-28T16:00:00"), zone, "Main St", emptyList()), calendar.inserted.single())
        assertEquals("2026-09-28T15:00", out.start)
    }

    @Test
    fun `bad input is rejected before anyone is asked to approve it`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { CreateEventInput("x", "2026-09-28T15:00", "2026-09-28T16:00", attendees = listOf("not-an-email")) }
        val asked = mutableListOf<String>()
        val gate = ApprovalGate { asked += it.summary; ApprovalDecision.Approved }
        val broker = ToolBroker(listOf(CreateEventTool(FakeCalendar()) { zone }), gate)
        val backwards = ToolCall("calendar_create_event", buildJsonObject {
            put("title", "Oops"); put("start", "2026-09-28T16:00"); put("end", "2026-09-28T15:00")
        })
        val outcome = broker.execute(backwards, RunState("r", "t", BrainLocation.ON_DEVICE, false))
        assertTrue(outcome.toString(), outcome is ToolOutcome.Invalid)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `times accept local, offset and date-only forms`() {
        assertEquals(at("2026-09-28T14:00:00"), parseTime("2026-09-28T14:00", zone))
        assertEquals(at("2026-09-28T14:00:00"), parseTime("2026-09-28T18:00Z", zone))
        assertEquals(at("2026-09-28T00:00:00"), parseTime("2026-09-28", zone))
        assertThrows(IllegalArgumentException::class.java) { parseTime("next tuesday", zone) }
    }
}
