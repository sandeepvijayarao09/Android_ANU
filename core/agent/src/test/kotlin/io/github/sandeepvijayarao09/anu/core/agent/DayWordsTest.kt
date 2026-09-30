package io.github.sandeepvijayarao09.anu.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DayWordsTest {
    // A Tuesday.
    private val today = LocalDate.of(2026, 9, 29)

    @Test
    fun `a weekday gets its coming date and its last one, and today's weekday is today`() {
        assertEquals("Thursday: coming Thu 2026-10-01, last Thu 2026-09-24", DayWords.hint("Remind me to call Maya on Thursday morning", today))
        assertEquals("Monday: coming Mon 2026-10-05, last Mon 2026-09-28", DayWords.hint("What did I do on Monday?", today))
        assertEquals("Wednesday: coming Wed 2026-09-30, last Wed 2026-09-23", DayWords.hint("WEDNESDAY works", today))
        assertEquals("Tuesday is today, Tue 2026-09-29", DayWords.hint("Any meetings Tuesday?", today))
    }

    @Test
    fun `today, tonight, yesterday, tomorrow and the day after tomorrow`() {
        assertEquals("tomorrow is Wed 2026-09-30; tonight is Tue 2026-09-29", DayWords.hint("Tomorrow, or tonight?", today))
        assertEquals("day after tomorrow is Thu 2026-10-01", DayWords.hint("the day after tomorrow at 3", today))
        assertEquals("yesterday is Mon 2026-09-28", DayWords.hint("Did Maya write yesterday?", today))
    }

    @Test
    fun `next Thursday and last Monday are left to the model, and a message with no day gets no hint`() {
        assertNull(DayWords.hint("next Thursday at 10", today))
        assertNull(DayWords.hint("How was last Monday's dinner?", today))
        assertNull(DayWords.hint("What's the capital of Portugal?", today))
    }
}
