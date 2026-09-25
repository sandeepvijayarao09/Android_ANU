package io.github.sandeepvijayarao09.anu.connector.calendar

import android.accounts.Account
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** The calendar connector against Android's real CalendarContract provider, in a throwaway local calendar. */
@RunWith(AndroidJUnit4::class)
class ProviderCalendarTest {

    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)

    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val account = Account("anu-test", CalendarContract.ACCOUNT_TYPE_LOCAL)
    private var calendarId = -1L

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
        .build()

    @Before
    fun createLocalCalendar() {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
            put(CalendarContract.Calendars.NAME, "anu-test")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Anu test")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.IS_PRIMARY, 1)
        }
        calendarId = ContentUris.parseId(resolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), values)!!)
    }

    @After
    fun deleteLocalCalendar() {
        resolver.delete(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)), null, null)
    }

    @Test
    fun insertedEventsWithAttendeesAreListedBack() = runBlocking {
        val calendar = ProviderCalendar(resolver)
        val start = Instant.parse("2030-03-04T14:00:00Z")
        val end = Instant.parse("2030-03-04T15:00:00Z")
        val id = calendar.insert(NewEvent("Design review", start, end, ZoneId.of("UTC"), "Room 4", listOf("maya@example.com")))

        val found = calendar.events(Instant.parse("2030-03-04T00:00:00Z"), Instant.parse("2030-03-05T00:00:00Z")).single { it.id == id }
        assertEquals("Design review", found.title)
        assertEquals(start, found.start)
        assertEquals(end, found.end)
        assertEquals("Room 4", found.location)
        assertEquals(listOf("maya@example.com"), found.attendees)
    }
}
