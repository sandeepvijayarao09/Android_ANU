package io.github.sandeepvijayarao09.anu.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class UpcomingTest {
    /** A Wednesday. */
    private val today = LocalDate.of(2026, 11, 11)

    private fun start(quote: String, starts: String? = null) = Upcoming.start(starts, quote, today)

    @Test
    fun `the fact's own words give the day`() {
        assertEquals(LocalDate.of(2026, 12, 1), start("I'm moving to Denver next month"))
        assertEquals(LocalDate.of(2026, 11, 16), start("I start my new job next week"))
        assertEquals(LocalDate.of(2027, 1, 1), start("We're moving to Oslo next year"))
        assertEquals(LocalDate.of(2026, 12, 2), start("I'm moving to Leeds in 3 weeks"))
        assertEquals(LocalDate.of(2027, 3, 1), start("I'm joining Tailspin in March"))
        assertEquals(LocalDate.of(2026, 11, 13), start("I start at Contoso on Friday"))
        assertEquals(LocalDate.of(2026, 11, 12), start("I'm starting at Fabrikam tomorrow"))
    }

    @Test
    fun `the words win over the model's arithmetic, and the model's date stands alone`() {
        assertEquals(LocalDate.of(2026, 12, 1), start("I'm moving to Denver next month", starts = "2026-11-12"))
        assertEquals(LocalDate.of(2027, 2, 14), start("Moving to Denver for the job", starts = "2027-02-14"))
        assertEquals(LocalDate.of(2026, 12, 1), start("I'm vegan from next month"))
        assertEquals(LocalDate.of(2027, 2, 1), start("Moving to Denver for the job", starts = "2027-02"))
    }

    @Test
    fun `a short quote is read with its sentence, and only its own sentence`() {
        assertEquals(LocalDate.of(2026, 12, 1), Upcoming.start("2026-12-11", "moving to Tallinn", today, "I'm moving to Tallinn next month"))
        assertEquals(
            LocalDate.of(2027, 3, 1),
            Upcoming.start(null, "joining Tailspin", today, "My sister's birthday is next week. I'm joining Tailspin in March."),
        )
    }

    @Test
    fun `the future tense with no day counts from a month on`() {
        assertEquals(today.plusDays(Upcoming.UNDATED_DAYS), start("I'm moving to Denver"))
        assertEquals(today.plusDays(Upcoming.UNDATED_DAYS), start("I'm going to move to Austin with my partner"))
    }

    @Test
    fun `what is true now, or already happened, has no day`() {
        assertNull(start("I live in Austin"))
        assertNull(start("I moved to Boston last month"))
        assertNull(start("I started at Fabrikam last week, my new job"))
        assertNull(start("I'll always be vegetarian"))
        assertNull(start("I'm vegetarian", starts = "2026-10-01"))
        assertNull(start("I work at Acme", starts = "not a date"))
        assertNull(start("Maya's birthday is in June"))
        assertNull(start("My dentist appointment is on Friday", starts = "2026-11-13"))
    }
}
