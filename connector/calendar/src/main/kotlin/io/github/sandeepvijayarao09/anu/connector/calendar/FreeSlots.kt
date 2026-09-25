package io.github.sandeepvijayarao09.anu.connector.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Finds free time between existing events, within working hours on each day (§10, AI Scheduling).
 * Pure, so it is unit-tested without a calendar. Slots start on a quarter hour and never fall in
 * the past.
 */
object FreeSlots {

    fun find(
        busy: List<ClosedRange<Instant>>,
        from: LocalDate,
        to: LocalDate,
        duration: Duration,
        earliest: LocalTime,
        latest: LocalTime,
        zone: ZoneId,
        now: Instant,
        limit: Int = 8,
    ): List<ClosedRange<Instant>> {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }
        require(earliest < latest) { "the day must start before it ends" }
        val merged = merge(busy)
        val slots = mutableListOf<ClosedRange<Instant>>()
        var day = from
        while (!day.isAfter(to) && slots.size < limit) {
            val dayStart = day.atTime(earliest).atZone(zone).toInstant()
            val dayEnd = day.atTime(latest).atZone(zone).toInstant()
            var cursor = roundUpToQuarter(maxOf(dayStart, now), zone)
            for (block in merged) {
                if (block.endInclusive <= cursor || block.start >= dayEnd) continue
                if (Duration.between(cursor, minOf(block.start, dayEnd)) >= duration) slots += cursor..cursor.plus(duration)
                if (slots.size >= limit) break
                cursor = roundUpToQuarter(maxOf(cursor, block.endInclusive), zone)
            }
            if (slots.size < limit && Duration.between(cursor, dayEnd) >= duration) slots += cursor..cursor.plus(duration)
            day = day.plusDays(1)
        }
        return slots.take(limit)
    }

    private fun merge(busy: List<ClosedRange<Instant>>): List<ClosedRange<Instant>> {
        val sorted = busy.sortedBy { it.start }
        val merged = mutableListOf<ClosedRange<Instant>>()
        for (block in sorted) {
            val last = merged.lastOrNull()
            if (last != null && block.start <= last.endInclusive) {
                merged[merged.lastIndex] = last.start..maxOf(last.endInclusive, block.endInclusive)
            } else {
                merged += block
            }
        }
        return merged
    }

    private fun roundUpToQuarter(instant: Instant, zone: ZoneId): Instant {
        val time = instant.atZone(zone)
        val extra = (15 - time.minute % 15) % 15
        val rounded = time.withSecond(0).withNano(0).plusMinutes(extra.toLong())
        return if (extra == 0 && (time.second > 0 || time.nano > 0)) rounded.plusMinutes(15).toInstant() else rounded.toInstant()
    }
}
