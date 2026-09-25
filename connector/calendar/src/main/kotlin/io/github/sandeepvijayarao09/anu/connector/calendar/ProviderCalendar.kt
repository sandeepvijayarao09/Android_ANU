package io.github.sandeepvijayarao09.anu.connector.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The phone's calendars through Android's `CalendarContract` provider (§9). This covers every account
 * synced to the device (Google, Exchange, Samsung), works offline, and needs `READ_CALENDAR` and
 * `WRITE_CALENDAR`. Recurring events are expanded through the Instances table.
 */
class ProviderCalendar(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CalendarSource {

    override suspend fun events(from: Instant, to: Instant): List<CalendarEvent> = withContext(io) {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toEpochMilli().toString())
            .appendPath(to.toEpochMilli().toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = mutableListOf<CalendarEvent>()
        resolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                events += CalendarEvent(
                    id = id,
                    title = c.getString(1).orEmpty(),
                    start = Instant.ofEpochMilli(c.getLong(2)),
                    end = Instant.ofEpochMilli(c.getLong(3)),
                    allDay = c.getInt(4) == 1,
                    location = c.getString(5)?.takeIf(String::isNotBlank),
                    attendees = attendees(id),
                )
            }
        }
        events
    }

    override suspend fun insert(event: NewEvent): Long = withContext(io) {
        val calendarId = primaryCalendarId() ?: error("There is no writable calendar on this phone")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.start.toEpochMilli())
            put(CalendarContract.Events.DTEND, event.end.toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, event.zone.id)
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (event.attendees.isNotEmpty()) put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("The calendar refused the event")
        val eventId = ContentUris.parseId(uri)
        event.attendees.forEach { email ->
            resolver.insert(
                CalendarContract.Attendees.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, eventId)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                },
            )
        }
        eventId
    }

    private fun attendees(eventId: Long): List<String> {
        val emails = mutableListOf<String>()
        resolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.takeIf(String::isNotBlank)?.let(emails::add) }
        return emails
    }

    /** The primary calendar the user can write to. If none is marked primary, the first owned one. */
    private fun primaryCalendarId(): Long? {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }
}
