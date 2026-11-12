package io.github.sandeepvijayarao09.anu.core.memory

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * When a fact comes true, if it hasn't yet (ADR-147). "I'm moving to Denver next month" isn't where the user lives
 * today: it is kept as upcoming, and replaces the old city on its day.
 *
 * The day comes from the fact's own words first, read here by rules, because a small model's date arithmetic is the
 * part it gets wrong: "next month", "next week", "next year", "in 3 weeks", "tomorrow", "in March", "on Friday". Then
 * from the date the model wrote, as YYYY-MM-DD or YYYY-MM. Words about the future without a day ("I'll be moving to
 * Denver") count from [UNDATED_DAYS] days on. A day that isn't after today means the fact is true now. Only a change
 * has a day it starts (a move, a new job, a switch): in "Maya's birthday is in June" the day is the fact.
 */
internal object Upcoming {
    /** How far off a fact told in the future tense, with no day, is taken to start. */
    const val UNDATED_DAYS = 30L

    /**
     * [message] is what the fact came from: the model's quote is often shorter than the sentence ("moving to Tallinn"
     * for "I'm moving to Tallinn next month", seen on the Pixel), so the sentence the quote is in is read too.
     */
    fun start(starts: String?, quote: String, today: LocalDate, message: String = quote): LocalDate? {
        val words = (quote + " " + sentenceOf(quote, message)).lowercase()
        // A day in a fact is usually what the fact is ("Maya's birthday is in June"); only a change starts on one.
        if (!CHANGE.containsMatchIn(words)) return null
        val day = fromWords(words, today)
            ?: starts?.trim()?.let { fromModel(it, today) ?: fromWords(it.lowercase(), today) }
            ?: if (FUTURE.containsMatchIn(words) && !PAST.containsMatchIn(words)) today.plusDays(UNDATED_DAYS) else null
        return day?.takeIf { it.isAfter(today) }
    }

    /** The sentence of [message] that holds every word of [quote], or nothing. */
    private fun sentenceOf(quote: String, message: String): String {
        val wanted = Normalize.words(quote).toSet()
        if (wanted.isEmpty()) return ""
        return message.split(Regex("(?<=[.!?;])\\s+|\\n")).firstOrNull { Normalize.words(it).containsAll(wanted) }.orEmpty()
    }

    private fun fromWords(text: String, today: LocalDate): LocalDate? {
        if (PAST.containsMatchIn(text)) return null
        if (Regex("\\btomorrow\\b").containsMatchIn(text)) return today.plusDays(1)
        if (Regex("\\bnext week\\b").containsMatchIn(text)) return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        if (Regex("\\bnext month\\b").containsMatchIn(text)) return today.plusMonths(1).withDayOfMonth(1)
        if (Regex("\\bnext year\\b").containsMatchIn(text)) return today.plusYears(1).withDayOfYear(1)
        Regex("\\bin (a|an|one|two|three|four|five|six|\\d{1,2}) (day|week|month|year)s?\\b").find(text)?.let { m ->
            val n = NUMBERS[m.groupValues[1]] ?: m.groupValues[1].toLong()
            return when (m.groupValues[2]) {
                "day" -> today.plusDays(n)
                "week" -> today.plusWeeks(n)
                "month" -> today.plusMonths(n)
                else -> today.plusYears(n)
            }
        }
        // "in March", "from March", "starting in March", "this March": the first of that month, the next time it comes.
        MONTH.find(text)?.let { m ->
            val month = MONTHS.getValue(m.groupValues[2])
            val first = today.withMonth(month.value).withDayOfMonth(1)
            return if (first.isAfter(today)) first else first.plusYears(1)
        }
        // "on Friday", "this Saturday": the next such day.
        WEEKDAY.find(text)?.let { m ->
            val day = DayOfWeek.entries.first { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() == m.groupValues[2] }
            return today.with(TemporalAdjusters.next(day))
        }
        return null
    }

    private fun fromModel(text: String, today: LocalDate): LocalDate? {
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(text)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("^(\\d{4})-(\\d{2})$").find(text)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1) }.getOrNull()
        }
        return null
    }

    private val NUMBERS = mapOf("a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L)

    private val MONTHS: Map<String, Month> = Month.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() }

    private val MONTH = Regex("\\b(in|from|starting|starting in|this|by|until|after)\\s+(${MONTHS.keys.joinToString("|")})\\b")

    private val WEEKDAY = Regex("\\b(on|this|next|starting)\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")

    /** Words that put a fact in the future: "I'm moving", "will start", "going to move", "starting at". Not a bare "I'll": "I'll always be vegetarian" is true now. */
    private val FUTURE = Regex(
        "\\b(i'm moving|im moving|i am moving|we're moving|moving to|will be moving|will move|will start|will join|going to move|going to start|" +
            "about to|plan to move|planning to move|starting at|start at|start my new|i'm starting|i am starting|i'm joining|i am joining)\\b",
    )

    /** Words for a change that starts on a day: a move, a new job, a switch. */
    private val CHANGE = Regex("\\b(mov(e|es|ing)|start(s|ing)?|join(s|ing)?|begin(s|ning)?|switch(es|ing)?|transferr?(ing)?|relocat(e|es|ing)|will be|from (next|this|the) )")

    /** Words that say it already happened: "last month", "ago", "moved". */
    private val PAST = Regex("\\b(last (week|month|year)|ago|yesterday|moved|joined|started|since)\\b")
}
