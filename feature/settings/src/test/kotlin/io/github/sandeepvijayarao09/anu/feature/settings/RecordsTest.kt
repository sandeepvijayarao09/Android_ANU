package io.github.sandeepvijayarao09.anu.feature.settings

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.InstallOutcome
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
        assertEquals("You edited and approved it, and it was done", outcomeLabel(action(io.github.sandeepvijayarao09.anu.core.agent.ToolBroker.OK_EDITED, true)))
        assertEquals("You declined", outcomeLabel(action("declined", false)))
        assertEquals("Done", outcomeLabel(action("ok", null)))
        assertEquals("Not answered in time, so not done", outcomeLabel(action("no answer in time", null)))
        assertEquals("Already done, not repeated", outcomeLabel(action("replayed", null)))
    }

    @Test
    fun `a model's space counts the weight cache it writes when it first runs`() {
        val e4b = ModelInfo(BrainId.PRO, "gemma-4-E4B-it.litertlm", 3_659_530_240, "CPU", cacheBytes = 2_210_334_416)
        assertEquals("needs 5.9 GB", modelSpace(ModelRow(e4b, ModelState.NotInstalled), Locale.US))
        assertEquals("before its first run, just the file", "3.7 GB used", modelSpace(ModelRow(e4b, ModelState.Ready, spaceUsed = 3_659_530_240), Locale.US))
        assertEquals("5.9 GB used", modelSpace(ModelRow(e4b, ModelState.Ready, spaceUsed = 5_869_864_656), Locale.US))
    }

    @Test
    fun `the models row shows a download first, then what's on the phone`() {
        fun row(id: BrainId, state: ModelState) = ModelRow(ModelInfo(id, "$id.litertlm", 1_000_000_000, "cpu"), state)
        assertEquals("None", modelsSummary(listOf(row(BrainId.BASE, ModelState.NotInstalled), row(BrainId.PRO, ModelState.NotInstalled))))
        assertEquals("Gemma 4 E4B", modelsSummary(listOf(row(BrainId.BASE, ModelState.NotInstalled), row(BrainId.PRO, ModelState.Ready))))
        assertEquals("E2B and E4B", modelsSummary(listOf(row(BrainId.BASE, ModelState.Ready), row(BrainId.PRO, ModelState.Ready))))
        assertEquals("Downloading 25%", modelsSummary(listOf(row(BrainId.BASE, ModelState.Downloading(25, 100)), row(BrainId.PRO, ModelState.Ready))))
    }

    @Test
    fun `a model the phone has too little memory for says so, and one it has enough for doesn't`() {
        val e4b = ModelInfo(BrainId.PRO, "gemma-4-E4B-it.litertlm", 3_659_530_240, "CPU", minMemoryGb = 12)
        assertEquals("Made for 12\u00A0GB of memory; this phone has 10\u00A0GB, so it may be slow", memoryNote(ModelRow(e4b, ModelState.Ready, deviceMemoryGb = 10)))
        assertNull(memoryNote(ModelRow(e4b, ModelState.Ready, deviceMemoryGb = 16)))
        assertNull("unknown memory", memoryNote(ModelRow(e4b, ModelState.Ready, deviceMemoryGb = 0)))
        assertNull("no guidance", memoryNote(ModelRow(e4b.copy(minMemoryGb = 0), ModelState.Ready, deviceMemoryGb = 4)))
    }

    @Test
    fun `a download says how long it has left once its speed is known`() {
        val gb = 1_000_000_000L
        assertNull("speed not known yet", timeLeft(ModelState.Downloading(0, 4 * gb)))
        assertEquals("about a minute left", timeLeft(ModelState.Downloading(3 * gb, 3 * gb + 30_000_000, bytesPerSecond = 1_000_000)))
        assertEquals("about 3 min left", timeLeft(ModelState.Downloading(gb, 3 * gb + 700_000_000, bytesPerSecond = 16_000_000)))
        assertEquals("about 1 h left", timeLeft(ModelState.Downloading(0, 3_600_000_000, bytesPerSecond = 1_000_000)))
        assertEquals("about 1 h 5 min left", timeLeft(ModelState.Downloading(0, 3_900_000_000, bytesPerSecond = 1_000_000)))
    }

    @Test
    fun `installing a file says what happened`() {
        assertEquals("Installed Gemma 4 E4B.", installMessage(InstallOutcome.Done("Gemma 4 E4B")))
        assertEquals(
            "Installed EmbeddingGemma's tokenizer. It also needs EmbeddingGemma 300M (embeddinggemma.tflite).",
            installMessage(InstallOutcome.Done("EmbeddingGemma's tokenizer", "EmbeddingGemma 300M (embeddinggemma.tflite)")),
        )
        assertEquals("Gemma 4 E4B is already installed.", installMessage(InstallOutcome.AlreadyThere("Gemma 4 E4B")))
        assertEquals("No.", installMessage(InstallOutcome.Refused("No.")))
    }

    @Test
    fun `speeds read as one short line, and in words for TalkBack`() {
        val counted = io.github.sandeepvijayarao09.anu.core.model.Speed(firstWordMs = 1_234, readPerSecond = 271.4, writePerSecond = 5.26, counted = true)
        assertEquals("first word 1.2 s · reads 271/s · writes 5.3/s", speedLine(counted))
        assertEquals("first word 1.2 seconds, reads 271 tokens a second, writes 5.3 tokens a second", speedWords(counted))
        val estimated = counted.copy(counted = false, readPerSecond = null)
        assertEquals("first word 1.2 s · writes about 5.3/s", speedLine(estimated))
        assertEquals("first word 1 min 50 s", speedLine(io.github.sandeepvijayarao09.anu.core.model.Speed(110_500, null, null)))
        assertEquals("first word 1 minute 50 seconds", speedWords(io.github.sandeepvijayarao09.anu.core.model.Speed(110_500, null, null)))
        assertEquals("first word 12 s", speedLine(io.github.sandeepvijayarao09.anu.core.model.Speed(12_300, null, null)))
    }

    @Test
    fun `the test's steps, the build and how long ago read plainly`() {
        assertEquals("Setting up the GPU. First time only, about a minute.", testStepLine(io.github.sandeepvijayarao09.anu.core.model.TestStep.GPU_SETUP, "Gemma 4 E4B"))
        assertEquals("Getting Gemma 4 E4B ready…", testStepLine(io.github.sandeepvijayarao09.anu.core.model.TestStep.LOADING, "Gemma 4 E4B"))
        assertEquals("Runs on the GPU", buildLine("GPU"))
        assertEquals("Runs on the NPU", buildLine("NPU_TENSOR_G5"))
        assertEquals("just now", ago(0, 30_000))
        assertEquals("2 min ago", ago(0, 120_000))
        assertEquals("3 h ago", ago(0, 3 * 3_600_000))
        assertEquals("2 days ago", ago(0, 49 * 3_600_000L))
    }
}
