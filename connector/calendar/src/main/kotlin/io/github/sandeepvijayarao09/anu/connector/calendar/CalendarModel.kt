package io.github.sandeepvijayarao09.anu.connector.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String?,
    val attendees: List<String>,
)

data class NewEvent(
    val title: String,
    val start: Instant,
    val end: Instant,
    val zone: ZoneId,
    val location: String?,
    val attendees: List<String>,
)

/** The calendar as the tools see it, so they can be tested without Android's provider. */
interface CalendarSource {
    /** Every occurrence overlapping [from, to), with recurring events expanded. */
    suspend fun events(from: Instant, to: Instant): List<CalendarEvent>

    /** Inserts into the user's primary writable calendar. Returns the new event id. */
    suspend fun insert(event: NewEvent): Long
}

/**
 * Times the model writes. Local ISO 8601, such as `2026-09-26T14:00`, is read in the user's zone. An
 * explicit offset is honored, and a plain date means the start of that day.
 */
internal fun parseTime(text: String, zone: ZoneId): Instant {
    val value = text.trim()
    return try {
        when {
            value.length == 10 -> LocalDate.parse(value).atStartOfDay(zone).toInstant()
            value.endsWith("Z") || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(value) -> OffsetDateTime.parse(value).toInstant()
            else -> LocalDateTime.parse(value).atZone(zone).toInstant()
        }
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("\"$text\" is not a date or time like 2026-09-26T14:00")
    }
}

internal val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
