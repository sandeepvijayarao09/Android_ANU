package io.github.sandeepvijayarao09.anu.connector.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** How reminders read their times and say when they come, and how apps are found by name (ADR-107). */
class IntentWordsTest {

    /** Saturday 2026-10-24, 2:30 PM. */
    private val now = LocalDateTime.of(2026, 10, 24, 14, 30)
    private val today: LocalDate = now.toLocalDate()

    @Test
    fun `a reminder's time is an ISO date and time, or a time of day at its next time`() {
        assertEquals(LocalDateTime.of(2026, 10, 24, 17, 0), reminderTime("2026-10-24T17:00", now))
        assertEquals(LocalDateTime.of(2026, 10, 30, 9, 15), reminderTime("2026-10-30T09:15:42", now))
        assertEquals(LocalDateTime.of(2026, 10, 24, 17, 0), reminderTime("17:00", now))
        assertEquals("passed today: tomorrow", LocalDateTime.of(2026, 10, 25, 9, 0), reminderTime("09:00", now))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `any other time names the form to use`() {
        reminderTime("5pm tomorrow", now)
    }

    @Test
    fun `when a reminder comes, in words`() {
        assertEquals("today at 5:00 PM", whenWords(LocalDateTime.of(2026, 10, 24, 17, 0), today))
        assertEquals("tomorrow at 9:30 AM", whenWords(LocalDateTime.of(2026, 10, 25, 9, 30), today))
        assertEquals("on Friday, October 30 at 8:00 AM", whenWords(LocalDateTime.of(2026, 10, 30, 8, 0), today))
        assertEquals("on Monday, January 4, 2027 at 12:00 PM", whenWords(LocalDateTime.of(2027, 1, 4, 12, 0), today))
    }

    @Test
    fun `the receipt says when, and whether it may be late`() {
        assertEquals("I'll remind you “call Mom” today at 5:00 PM.", reminderSaid("call Mom", "today at 5:00 PM", exact = true))
        val late = reminderSaid("call Mom", "today at 5:00 PM", exact = false)
        assertTrue(late, late.startsWith("I'll remind you “call Mom” today at 5:00 PM. It may come up to 10 minutes late"))
    }

    @Test
    fun `a reminder to cancel is found by its exact words, else by every word`() {
        val all = listOf(Reminder(1, "call Mom", 0), Reminder(2, "call the bank", 0), Reminder(3, "Call mom about dinner", 0))
        assertEquals(listOf(1L), matchReminders("Call mom", all).map { it.id })
        assertEquals(listOf(2L), matchReminders("bank", all).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), matchReminders("call", all).map { it.id })
        assertEquals(listOf(3L), matchReminders("to call mom about dinner", all).map { it.id })
        assertEquals(emptyList<Reminder>(), matchReminders("dentist", all))
    }

    @Test
    fun `an app is its exact name, else the one that starts so, else the one that holds it`() {
        val apps = listOf(
            LauncherApp("Maps", "com.google.android.apps.maps"),
            LauncherApp("Play Store", "com.android.vending"),
            LauncherApp("Play Games", "com.google.android.play.games"),
            LauncherApp("Spotify", "com.spotify.music"),
            LauncherApp("Google Messages", "com.google.android.apps.messaging"),
        )
        assertEquals(AppPick.One(apps[0]), pickApp("maps", apps))
        assertEquals(AppPick.One(apps[3]), pickApp("the Spotify app", apps))
        assertEquals(AppPick.One(apps[3]), pickApp("spot", apps))
        assertEquals(AppPick.One(apps[4]), pickApp("messages", apps))
        assertEquals(AppPick.Several(listOf("Play Games", "Play Store")), pickApp("play", apps))
        assertEquals(AppPick.None, pickApp("door", apps))
        assertEquals(AppPick.None, pickApp("  ", apps))
    }

    @Test
    fun `a number to call`() {
        assertTrue(isPhoneNumber("555-0123"))
        assertTrue(isPhoneNumber("+1 (555) 010-0199"))
        assertFalse(isPhoneNumber("Mom"))
        assertFalse(isPhoneNumber("--"))
    }
}
