package io.github.sandeepvijayarao09.anu.core.agent

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The dates of the days a message names, so a small model needn't work them out (§7.3, ADR-085): "Thursday: coming
 * Thu 2026-10-01, last Thu 2026-09-24". The voice note eval saw E4B put "Thursday" on a Wednesday three times in
 * five, with the week's dates in its system prompt. A weekday's name gets both its coming date and its last one,
 * since "on Monday" can be either ("Remind me on Monday", "What did I do on Monday?") and the model can tell which
 * from the words; the phone only does the counting. Today's own weekday is today. "Today", "tonight", "yesterday",
 * "tomorrow" and "the day after tomorrow" mean what they say. "Next Thursday" and "last Monday" are left to the model,
 * since people mean different days by them.
 */
object DayWords {
    private val DAY = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.ENGLISH)
    private val WORDS = Regex(
        "\\b(next\\s+|last\\s+)?(day after tomorrow|yesterday|tomorrow|today|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "Thursday: coming Thu 2026-10-01, last Thu 2026-09-24; tomorrow is Wed 2026-09-30", or null when [text] names no day. */
    fun hint(text: String, today: LocalDate): String? {
        val named = linkedMapOf<String, String>()
        for (match in WORDS.findAll(text)) {
            if (match.groupValues[1].isNotEmpty()) continue
            val word = match.groupValues[2].lowercase()
            named.getOrPut(word) {
                when (word) {
                    "today", "tonight" -> "$word is ${DAY.format(today)}"
                    "yesterday" -> "$word is ${DAY.format(today.minusDays(1))}"
                    "tomorrow" -> "$word is ${DAY.format(today.plusDays(1))}"
                    "day after tomorrow" -> "$word is ${DAY.format(today.plusDays(2))}"
                    else -> {
                        val name = word.replaceFirstChar(Char::uppercase)
                        val ahead = (DayOfWeek.valueOf(word.uppercase()).value - today.dayOfWeek.value + 7) % 7L
                        if (ahead == 0L) {
                            "$name is today, ${DAY.format(today)}"
                        } else {
                            "$name: coming ${DAY.format(today.plusDays(ahead))}, last ${DAY.format(today.plusDays(ahead - 7))}"
                        }
                    }
                }
            }
        }
        return named.values.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
