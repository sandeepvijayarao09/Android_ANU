package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    fun match(message: String): ToolCall? {
        val text = message.lowercase().trim().trimEnd('.', '!', '?').removePrefix("please ").removeSuffix(" please").trim()
        return timer(text) ?: alarm(text) ?: flashlight(text) ?: doNotDisturb(text) ?: settings(text)
    }

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

}
