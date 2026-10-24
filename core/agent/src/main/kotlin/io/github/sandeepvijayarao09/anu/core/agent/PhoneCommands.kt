package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Simple phone commands that need no model (G6, ADR-076). A message that is exactly one of them becomes one tool call,
 * which runs through the broker like any other: its risk tier, taint and audit all apply. Anything more, such as
 * "set a timer for ten minutes and email Maya the agenda", goes to the model as usual, so nothing is half done.
 */
object PhoneCommands {
    // Declared first: an object's properties are set in order, and the patterns below use these.
    /** A day, the longest timer a clock app takes. */
    private const val MAX_TIMER_SECONDS = 86_400

    private val WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "twenty-five" to 25, "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )

    private val NUMBER = "\\d{1,5}|" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private const val UNIT = "seconds?|secs?|minutes?|mins?|hours?|hrs?"
    private const val TIME = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)?"

    private fun number(word: String): Int? = word.toIntOrNull() ?: WORDS[word]

    /**
     * The one tool call [message] is, or null. [now] is the phone's local time, for reminders. [hasApp] says whether an
     * installed app has a name, so "open the door" goes to the model rather than failing to find an app (ADR-107).
     */
    fun match(message: String, now: LocalDateTime = LocalDateTime.now(), hasApp: (String) -> Boolean = { true }): ToolCall? {
        // Reminders, places and apps keep the user's own capitals; the rest read the words in lower case.
        val said = message.trim().trimEnd('.', '!', '?').replace(PLEASE_FIRST, "").replace(PLEASE_LAST, "").trim()
        val text = said.lowercase()
        return timer(text) ?: alarm(text) ?: flashlight(text) ?: doNotDisturb(text) ?: brightness(text) ?: volume(text) ?: settings(text)
            ?: reminder(said, now) ?: reminders(text) ?: cancelReminder(said) ?: call(text) ?: navigate(said) ?: openApp(said, hasApp)
    }

    private val PLEASE_FIRST = Regex("^please,? ", RegexOption.IGNORE_CASE)
    private val PLEASE_LAST = Regex(",? please$", RegexOption.IGNORE_CASE)

    private val TIMER = listOf(
        Regex("^(?:set|start)(?: a| an)? timer (?:for )?($NUMBER) ($UNIT)$"),
        Regex("^(?:set|start)(?: a| an)? ($NUMBER)[ -]($UNIT) timer$"),
        Regex("^timer (?:for )?($NUMBER) ($UNIT)$"),
    )

    private fun timer(text: String): ToolCall? {
        val found = TIMER.firstNotNullOfOrNull { it.find(text) } ?: return null
        val amount = number(found.groupValues[1]) ?: return null
        val unit = found.groupValues[2]
        val seconds = amount * when {
            unit.startsWith("h") -> 3_600
            unit.startsWith("m") -> 60
            else -> 1
        }
        if (seconds !in 1..MAX_TIMER_SECONDS) return null
        return ToolCall("phone_set_timer", buildJsonObject { put("seconds", seconds) })
    }

    private val ALARM = listOf(
        Regex("^(?:set|create)(?: an)? alarm (?:for|at) $TIME$"),
        Regex("^wake me(?: up)? at $TIME$"),
        Regex("^alarm (?:for|at) $TIME$"),
    )

    private fun alarm(text: String): ToolCall? {
        val found = ALARM.firstNotNullOfOrNull { it.find(text) } ?: return null
        var hour = found.groupValues[1].toInt()
        val minute = found.groupValues[2].ifEmpty { "0" }.toInt()
        val half = found.groupValues[3].replace(".", "")
        if (minute > 59) return null
        when (half) {
            "am" -> if (hour == 12) hour = 0 else if (hour > 12) return null
            "pm" -> if (hour in 1..11) hour += 12 else if (hour != 12) return null
            else -> if (hour > 23) return null
        }
        return ToolCall("phone_set_alarm", buildJsonObject { put("hour", hour); put("minute", minute) })
    }

    private val FLASHLIGHT = listOf(
        Regex("^(?:turn|switch) (on|off) (?:the )?(?:flashlight|torch)$"),
        Regex("^(?:turn|switch) (?:the )?(?:flashlight|torch) (on|off)$"),
        Regex("^(?:flashlight|torch) (on|off)$"),
    )

    private fun flashlight(text: String): ToolCall? {
        val found = FLASHLIGHT.firstNotNullOfOrNull { it.find(text) } ?: return null
        return ToolCall("phone_flashlight", buildJsonObject { put("on", found.groupValues[1] == "on") })
    }

    private val DO_NOT_DISTURB = listOf(
        Regex("^(?:turn|switch) (on|off) (?:do not disturb|dnd)$"),
        Regex("^(?:turn|switch) (?:do not disturb|dnd) (on|off)$"),
        Regex("^(?:do not disturb|dnd) (on|off)$"),
    )

    private fun doNotDisturb(text: String): ToolCall? {
        val found = DO_NOT_DISTURB.firstNotNullOfOrNull { it.find(text) } ?: return null
        return ToolCall("phone_do_not_disturb", buildJsonObject { put("on", found.groupValues[1] == "on") })
    }

    /** A level: "40", "40%", "40 percent", or max, min and their kin (ADR-099). */
    private const val LEVEL = "(\\d{1,3})\\s*(?:%|percent)?|max|maximum|full|highest|min|minimum|lowest"

    private fun level(word: String): Int? = when (word) {
        "max", "maximum", "full", "highest" -> 100
        "min", "minimum", "lowest" -> 0
        else -> word.removeSuffix("percent").trimEnd('%', ' ').toIntOrNull()?.takeIf { it in 0..100 }
    }

    private val BRIGHTNESS = listOf(
        Regex("^(?:set|change|turn|put|make)(?: the| my)?(?: screen)? brightness (?:to |at |up to |down to )?($LEVEL)$"),
        Regex("^(?:screen )?brightness (?:to |at )?($LEVEL)$"),
    )

    private fun brightness(text: String): ToolCall? {
        val found = BRIGHTNESS.firstNotNullOfOrNull { it.find(text) } ?: return null
        val percent = level(found.groupValues[1]) ?: return null
        return ToolCall("phone_brightness", buildJsonObject { put("percent", percent) })
    }

    private const val STREAM = "media|music|ring|ringer|ringtone|alarm|notification|notifications"

    private val VOLUME = listOf(
        Regex("^(?:set|change|turn|put)(?: the| my)?(?: ($STREAM))? volume (?:to |at |up to |down to )?($LEVEL)$"),
        Regex("^(?:($STREAM) )?volume (?:to |at )?($LEVEL)$"),
    )

    private fun volume(text: String): ToolCall? {
        val found = VOLUME.firstNotNullOfOrNull { it.find(text) } ?: return null
        val percent = level(found.groupValues[2]) ?: return null
        val stream = when (found.groupValues[1]) {
            "ring", "ringer", "ringtone" -> "ring"
            "alarm" -> "alarm"
            "notification", "notifications" -> "notification"
            else -> "media"
        }
        return ToolCall("phone_volume", buildJsonObject { put("percent", percent); put("stream", stream) })
    }

    private val SETTINGS = Regex("^(?:open|show)(?: the| my)? (wi-?fi|bluetooth|battery|display|sound|location|notifications?|accessibility) settings$")

    private fun settings(text: String): ToolCall? {
        val page = SETTINGS.find(text)?.groupValues?.get(1) ?: return null
        val name = when {
            page.startsWith("wi") -> "wifi"
            page.startsWith("notification") -> "notifications"
            else -> page
        }
        return ToolCall("phone_open_settings", buildJsonObject { put("page", name) })
    }

    // Reminders (ADR-107): "remind me at 5 pm to call Mom", "remind me to call Mom tomorrow at 9 am", "remind me in 20
    // minutes to check the oven". A time with no AM or PM and no day is its next time, either way ("at 5" after noon is
    // 5 PM); with a day, it's left to the model.
    private val REMIND = Regex("^(?:remind me|set a reminder|set reminder|create a reminder|add a reminder) (.+)$", RegexOption.IGNORE_CASE)
    private val WHEN_LAST = Regex(" (?=(?:today |tonight |tomorrow )?(?:at|in) )", RegexOption.IGNORE_CASE)
    private val WHEN_FIRST = Regex(",? (?:to|about|that|for) ", RegexOption.IGNORE_CASE)
    private val TASK_START = Regex("^(?:to|about|that|for) ", RegexOption.IGNORE_CASE)
    private val AT = Regex("^(?:(today|tonight|tomorrow) )?at $TIME(?: (today|tonight|tomorrow))?$")
    private val IN = Regex("^in (half an hour|(?:$NUMBER) (?:$UNIT))$")
    private val ISO_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private const val MAX_REMINDER = 200

    private fun reminder(said: String, now: LocalDateTime): ToolCall? {
        val rest = REMIND.find(said)?.groupValues?.get(1) ?: return null
        // The time last: the first place where the rest of the message is exactly a time.
        for (split in WHEN_LAST.findAll(rest)) {
            val at = reminderTime(rest.substring(split.range.last + 1).lowercase(), now) ?: continue
            return reminderCall(rest.substring(0, split.range.first).replace(TASK_START, ""), at)
        }
        // The time first: "at 5 pm to call Mom".
        for (split in WHEN_FIRST.findAll(rest)) {
            val at = reminderTime(rest.substring(0, split.range.first).lowercase(), now) ?: continue
            return reminderCall(rest.substring(split.range.last + 1), at)
        }
        return null
    }

    private fun reminderCall(task: String, at: LocalDateTime): ToolCall? {
        val words = task.trim()
        if (words.isEmpty() || words.length > MAX_REMINDER) return null
        return ToolCall("phone_set_reminder", buildJsonObject { put("text", words); put("at", ISO_MINUTE.format(at)) })
    }

    /** The local time [words] name, such as "at 5 pm", "tomorrow at 9:30 am" or "in 20 minutes", after [now]; else null. */
    private fun reminderTime(words: String, now: LocalDateTime): LocalDateTime? {
        val start = now.withSecond(0).withNano(0)
        IN.find(words)?.let { found ->
            val minutes = if (found.groupValues[1] == "half an hour") 30 else {
                val (amount, unit) = found.groupValues[1].split(' ', limit = 2)
                val count = number(amount) ?: return null
                when {
                    unit.startsWith("h") -> count * 60
                    unit.startsWith("m") -> count
                    else -> return null
                }
            }
            return if (minutes in 1..MAX_TIMER_SECONDS / 60) start.plusMinutes(minutes.toLong()) else null
        }
        val found = AT.find(words) ?: return null
        val day = found.groupValues[1].ifEmpty { found.groupValues[5] }
        if (found.groupValues[1].isNotEmpty() && found.groupValues[5].isNotEmpty()) return null
        var hour = found.groupValues[2].toInt()
        val minute = found.groupValues[3].ifEmpty { "0" }.toInt()
        val half = found.groupValues[4].replace(".", "")
        if (minute > 59) return null
        when {
            half == "am" -> if (hour == 12) hour = 0 else if (hour !in 1..11) return null
            half == "pm" -> if (hour in 1..11) hour += 12 else if (hour != 12) return null
            hour > 23 -> return null
            day == "tonight" && hour in 1..11 -> hour += 12
            // "At 5" or "at 5:30": whichever of AM and PM comes next. With a day, it's left to the model. "09:00" is 24-hour.
            hour in 1..12 && !found.groupValues[2].startsWith("0") -> {
                if (day.isNotEmpty()) return null
                return minOf(nextTime(start, hour % 12, minute), nextTime(start, hour % 12 + 12, minute))
            }
        }
        val today = start.toLocalDate().atTime(hour, minute)
        return when (day) {
            "today", "tonight" -> today.takeIf { it.isAfter(now) }
            "tomorrow" -> today.plusDays(1)
            else -> nextTime(start, hour, minute)
        }
    }

    private fun nextTime(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime =
        now.toLocalDate().atTime(hour, minute).let { if (it.isAfter(now)) it else it.plusDays(1) }

    private val LIST_REMINDERS = Regex("^(?:what are|what're|show|show me|list|read)(?: all)? my reminders$|^(?:my|any) reminders$|^do i have any reminders$")

    private fun reminders(text: String): ToolCall? =
        if (LIST_REMINDERS.matches(text)) ToolCall("phone_list_reminders", buildJsonObject {}) else null

    private val CANCEL_REMINDER = Regex("^(?:cancel|delete|remove|clear) (?:my |the )?reminder (?:to |about |for )?(.+)$", RegexOption.IGNORE_CASE)

    private fun cancelReminder(said: String): ToolCall? {
        val words = CANCEL_REMINDER.find(said)?.groupValues?.get(1)?.trim() ?: return null
        return ToolCall("phone_cancel_reminder", buildJsonObject { put("words", words) })
    }

    // Other apps through Android's intents (ADR-107). A number only: "call Mom" needs contacts_find, so the model.
    private val CALL = Regex("^(?:call|dial|phone|ring) (\\+?[\\d ().-]{3,20})$")

    private fun call(text: String): ToolCall? {
        val number = CALL.find(text)?.groupValues?.get(1)?.trim() ?: return null
        if (number.count(Char::isDigit) < 3) return null
        return ToolCall("phone_call", buildJsonObject { put("number", number) })
    }

    private val NAVIGATE = Regex(
        "^(?:navigate|(?:get |give me |show me )?directions|take me|drive me|drive|route me|how do i get) to (.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val MORE = Regex("\\b(?:and|then)\\b", RegexOption.IGNORE_CASE)

    private fun navigate(said: String): ToolCall? {
        val place = NAVIGATE.find(said)?.groupValues?.get(1)?.trim() ?: return null
        if (place.length > 80 || MORE.containsMatchIn(place)) return null
        return ToolCall("phone_navigate", buildJsonObject { put("destination", place) })
    }

    /** "Open Spotify". "Open the Clock app" is app control's, which needs the word "app" (ADR-098). */
    private val OPEN = Regex("^(?:open|launch|start) (?:the |my )?(.+)$", RegexOption.IGNORE_CASE)

    private fun openApp(said: String, hasApp: (String) -> Boolean): ToolCall? {
        val name = OPEN.find(said)?.groupValues?.get(1)?.trim() ?: return null
        if (name.lowercase().endsWith(" app") || MORE.containsMatchIn(name) || name.split(' ').size > 4 || !hasApp(name)) return null
        return ToolCall("phone_open_app", buildJsonObject { put("name", name) })
    }
}
