package io.github.sandeepvijayarao09.anu.feature.settings

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ModelInfo
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PiiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class RecordsTest {

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 9, 26)

    private fun at(day: LocalDate, hour: Int = 12) = day.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()

    private fun entry(day: LocalDate, hour: Int = 12, destination: String = "notes.example.com") =
        LedgerEntry(at(day, hour), destination, "MCP Notes: add_note", setOf(DataClass.PERSONAL), emptyMap(), 48)

    @Test
    fun `records fall into today, the six days before, and earlier`() {
        assertEquals(Period.TODAY, periodOf(at(today, 0), today, zone))
        assertEquals(Period.TODAY, periodOf(at(today.plusDays(1)), today, zone)) // a clock ahead still reads as today
        assertEquals(Period.WEEK, periodOf(at(today.minusDays(1), 23), today, zone))
        assertEquals(Period.WEEK, periodOf(at(today.minusDays(6)), today, zone))
        assertEquals(Period.EARLIER, periodOf(at(today.minusDays(7)), today, zone))
    }

    @Test
    fun `periods list newest first and leave out empty ones`() {
        val old = entry(today.minusDays(30))
        val morning = entry(today, 9)
        val evening = entry(today, 20)
        val groups = byPeriod(listOf(old, morning, evening), today, zone) { it.at }
        assertEquals(listOf(Period.TODAY, Period.EARLIER), groups.map { it.period })
        assertEquals(listOf(evening, morning), groups.first().items)
    }

    @Test
    fun `the seven-day row counts real sends, oldest first, ending today`() {
        val entries = listOf(entry(today), entry(today), entry(today.minusDays(3)), entry(today.minusDays(9)))
        val week = lastSevenDays(entries, today, zone)
        assertEquals(7, week.size)
        assertEquals(today.minusDays(6), week.first().day)
        assertEquals(today, week.last().day)
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 2), week.map { it.count })
    }

    @Test
    fun `the headline counts today`() {
        assertEquals("Nothing left your phone today", reportHeadline(0))
        assertEquals("1 item left your phone today", reportHeadline(1))
        assertEquals("3 items left your phone today", reportHeadline(3))
    }

    @Test
    fun `known Google APIs get names, anything else shows its host`() {
        assertEquals("Gemini, Google", destinationName("generativelanguage.googleapis.com"))
        assertEquals("Gmail, your account", destinationName("gmail.googleapis.com"))
        assertEquals("10.0.2.2", destinationName("10.0.2.2"))
    }

    @Test
    fun `redactions read as plain words`() {
        assertNull(redactionText(emptyMap()))
        assertNull(redactionText(mapOf(PiiKind.EMAIL to 0)))
        assertEquals("2 email addresses replaced", redactionText(mapOf(PiiKind.EMAIL to 2)))
        assertEquals("1 email address, 1 phone number replaced", redactionText(mapOf(PiiKind.PHONE to 1, PiiKind.EMAIL to 1)))
        assertEquals("3 card numbers replaced", redactionText(mapOf(PiiKind.CARD to 3)))
    }

    @Test
    fun `sizes are exact`() {
        assertEquals("1 char", characterCount(1, Locale.US))
        assertEquals("48 chars", characterCount(48, Locale.US))
        assertEquals("1,204 chars", characterCount(1204, Locale.US))
        assertEquals("3.7 GB", gigabytes(3_654_000_000, Locale.US))
    }

    @Test
    fun `outcomes say what happened`() {
        fun action(outcome: String, approved: Boolean?) = ToolAuditEntry("run", "email_send", RiskTier.WRITE_EXTERNAL, "Send an email", outcome, approved, 0)
        assertEquals("You approved, and it was done", outcomeLabel(action("ok", true)))
        assertEquals("You declined", outcomeLabel(action("declined", false)))
        assertEquals("Done", outcomeLabel(action("ok", null)))
        assertEquals("Not answered in time, so not done", outcomeLabel(action("no answer in time", null)))
        assertEquals("Already done, not repeated", outcomeLabel(action("replayed", null)))
    }

    @Test
    fun `the models row shows a download first, then what's on the phone`() {
        fun row(id: BrainId, state: ModelState) = ModelRow(ModelInfo(id, "$id.litertlm", 1_000_000_000, "cpu"), state)
        assertEquals("None", modelsSummary(listOf(row(BrainId.BASE, ModelState.NotInstalled), row(BrainId.PRO, ModelState.NotInstalled))))
        assertEquals("Gemma 4 E4B", modelsSummary(listOf(row(BrainId.BASE, ModelState.NotInstalled), row(BrainId.PRO, ModelState.Ready))))
        assertEquals("E2B and E4B", modelsSummary(listOf(row(BrainId.BASE, ModelState.Ready), row(BrainId.PRO, ModelState.Ready))))
        assertEquals("Downloading 25%", modelsSummary(listOf(row(BrainId.BASE, ModelState.Downloading(25, 100)), row(BrainId.PRO, ModelState.Ready))))
    }
}
