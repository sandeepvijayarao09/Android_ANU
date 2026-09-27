package io.github.sandeepvijayarao09.anu.connector.calendar

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val SPOKEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun Instant.local(zone: ZoneId): String = LOCAL.format(atZone(zone))

@Serializable
data class EventsInput(
    @Description("Start of the range, local date or time, e.g. 2026-09-26 or 2026-09-26T09:00") val from: String,
    @Description("End of the range, exclusive") val to: String,
)

@Serializable
data class EventOut(
    val title: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val location: String? = null,
    val attendees: List<String> = emptyList(),
)

@Serializable
data class EventsOutput(val events: List<EventOut>)

/** Lists events. Their titles and locations can be written by others through invites, so they are untrusted (P4). */
class ListEventsTool(private val calendar: CalendarSource, private val zone: () -> ZoneId) : Tool<EventsInput, EventsOutput> {
    override val name = "calendar_list_events"
    override val description = "List the user's calendar events in a time range."
    override val input = EventsInput.serializer()
    override val output = EventsOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = true

    override fun risk(input: EventsInput) = RiskTier.READ
    override fun describe(input: EventsInput) = "Read calendar events from ${input.from} to ${input.to}"

    override suspend fun invoke(input: EventsInput, context: ToolContext): EventsOutput {
        val zone = zone()
        val events = calendar.events(parseTime(input.from, zone), parseTime(input.to, zone))
        return EventsOutput(
            events.take(MAX_EVENTS).map {
                EventOut(it.title, it.start.local(zone), it.end.local(zone), it.allDay, it.location, it.attendees.take(MAX_ATTENDEES))
            },
        )
    }

    private companion object {
        const val MAX_EVENTS = 25
        const val MAX_ATTENDEES = 10
    }
}

@Serializable
data class SlotsInput(
    @Description("Meeting length in minutes") val durationMinutes: Int,
    @Description("First day to search, e.g. 2026-09-28") val fromDate: String,
    @Description("Last day to search, inclusive") val toDate: String,
    @Description("Earliest start hour, 0-23") val earliestHour: Int = 9,
    @Description("Latest end hour, 1-24") val latestHour: Int = 18,
) {
    init {
        require(durationMinutes in 5..(12 * 60)) { "durationMinutes must be between 5 and 720" }
        require(earliestHour in 0..23 && latestHour in 1..24 && earliestHour < latestHour) { "hours must satisfy 0 <= earliest < latest <= 24" }
    }
}

@Serializable
data class SlotOut(val start: String, val end: String)

@Serializable
data class SlotsOutput(val slots: List<SlotOut>)

/** Finds free time. It returns only times, never event content, so its results are not untrusted. */
class FindFreeSlotsTool(
    private val calendar: CalendarSource,
    private val zone: () -> ZoneId,
    private val now: () -> Instant = Instant::now,
) : Tool<SlotsInput, SlotsOutput> {
    override val name = "calendar_find_free_slots"
    override val description = "Find free time slots in the user's calendar on the given days."
    override val input = SlotsInput.serializer()
    override val output = SlotsOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = false

    override fun risk(input: SlotsInput) = RiskTier.READ
    override fun describe(input: SlotsInput) = "Find ${input.durationMinutes}-minute free slots from ${input.fromDate} to ${input.toDate}"

    override suspend fun invoke(input: SlotsInput, context: ToolContext): SlotsOutput {
        val zone = zone()
        val from = LocalDate.parse(input.fromDate.take(10))
        val to = LocalDate.parse(input.toDate.take(10))
        require(!to.isBefore(from) && to.toEpochDay() - from.toEpochDay() <= 31) { "search at most 31 days, forwards" }
        val busy = calendar.events(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
            .filterNot { it.allDay }
            .map { it.start..it.end }
        val latest = if (input.latestHour == 24) LocalTime.MAX else LocalTime.of(input.latestHour, 0)
        val slots = FreeSlots.find(busy, from, to, Duration.ofMinutes(input.durationMinutes.toLong()), LocalTime.of(input.earliestHour, 0), latest, zone, now())
        return SlotsOutput(slots.map { SlotOut(it.start.local(zone), it.endInclusive.local(zone)) })
    }
}

@Serializable
data class CreateEventInput(
    @Description("Event title") val title: String,
    @Description("Local start time, e.g. 2026-09-26T14:00") val start: String,
    @Description("Local end time, after start") val end: String,
    @Description("Optional place") val location: String? = null,
    @Description("Email addresses to invite. Leave empty for a private event") val attendees: List<String> = emptyList(),
) {
    init {
        require(title.isNotBlank() && title.length <= 200) { "title must be 1-200 characters" }
        require(attendees.size <= 50) { "at most 50 attendees" }
        attendees.forEach { require(EMAIL.matches(it)) { "\"$it\" is not an email address" } }
    }
}

@Serializable
data class CreatedOut(val eventId: Long, val start: String, val end: String)

/** Creates an event. With attendees, the calendar's account sends invites, so it becomes WRITE_EXTERNAL (§9). */
class CreateEventTool(private val calendar: CalendarSource, private val zone: () -> ZoneId) : Tool<CreateEventInput, CreatedOut> {
    override val name = "calendar_create_event"
    override val description = "Create an event in the user's calendar, optionally inviting people by email."
    override val input = CreateEventInput.serializer()
    override val output = CreatedOut.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = false

    override fun risk(input: CreateEventInput) = if (input.attendees.isEmpty()) RiskTier.WRITE_PRIVATE else RiskTier.WRITE_EXTERNAL
    // Times stay as they are: typed as text they'd be easy to get wrong. Adding invitees makes it an invite.
    override val editable = listOf(
        EditableField("title", "Title", EditableField.Kind.LINE),
        EditableField("location", "Place", EditableField.Kind.LINE, optional = true),
        EditableField("attendees", "Invite", EditableField.Kind.LIST),
    )

    override fun describe(input: CreateEventInput): String {
        val zone = zone()
        val (start, end) = span(input, zone)
        val sameDay = start.atZone(zone).toLocalDate() == end.atZone(zone).toLocalDate()
        val whenText = SPOKEN.format(start.atZone(zone)) + "–" + (if (sameDay) CLOCK else SPOKEN).format(end.atZone(zone))
        return buildString {
            append("Add \"").append(input.title).append("\" on ").append(whenText)
            input.location?.takeIf(String::isNotBlank)?.let { append(" at ").append(it) }
            if (input.attendees.isEmpty()) append(" to your calendar") else append(" and invite ").append(input.attendees.joinToString())
        }
    }

    override suspend fun invoke(input: CreateEventInput, context: ToolContext): CreatedOut {
        val zone = zone()
        val (start, end) = span(input, zone)
        val id = calendar.insert(NewEvent(input.title.trim(), start, end, zone, input.location?.trim(), input.attendees))
        return CreatedOut(id, start.local(zone), end.local(zone))
    }

    private fun span(input: CreateEventInput, zone: ZoneId): Pair<Instant, Instant> {
        val start = parseTime(input.start, zone)
        val end = parseTime(input.end, zone)
        require(end > start) { "the end must be after the start" }
        require(Duration.between(start, end) <= Duration.ofDays(1)) { "events can be at most a day long" }
        return start to end
    }
}
