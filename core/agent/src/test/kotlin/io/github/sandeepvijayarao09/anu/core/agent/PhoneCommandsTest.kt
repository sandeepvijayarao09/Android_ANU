package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** Which messages are exactly one phone command (G6, ADR-076), and which go to the model. */
class PhoneCommandsTest {

    private fun timer(seconds: Int) = buildJsonObject { put("seconds", seconds) }

    @Test
    fun `timers in the ways people ask`() {
        assertEquals(timer(600), PhoneCommands.match("Set a timer for 10 minutes")?.arguments)
        assertEquals(timer(600), PhoneCommands.match("set a timer for ten minutes.")?.arguments)
        assertEquals(timer(300), PhoneCommands.match("Please start a 5 minute timer")?.arguments)
        assertEquals(timer(3_600), PhoneCommands.match("timer for an hour")?.arguments)
        assertEquals(timer(30), PhoneCommands.match("Set timer 30 seconds")?.arguments)
        assertEquals("phone_set_timer", PhoneCommands.match("set a 2-hour timer")?.name)
    }

    @Test
    fun `alarms on either clock`() {
        assertEquals(buildJsonObject { put("hour", 7); put("minute", 0) }, PhoneCommands.match("Set an alarm for 7 am")?.arguments)
        assertEquals(buildJsonObject { put("hour", 18); put("minute", 45) }, PhoneCommands.match("set an alarm for 6:45 pm")?.arguments)
        assertEquals(buildJsonObject { put("hour", 0); put("minute", 30) }, PhoneCommands.match("Wake me up at 12:30 a.m.")?.arguments)
        assertEquals(buildJsonObject { put("hour", 21); put("minute", 0) }, PhoneCommands.match("alarm at 21:00")?.arguments)
        assertNull("no such time", PhoneCommands.match("set an alarm for 13 pm"))
        assertNull(PhoneCommands.match("set an alarm for 7:75"))
    }

    @Test
    fun `brightness and volume as a level (ADR-099)`() {
        fun call(text: String) = PhoneCommands.match(text)?.let { it.name to it.arguments }
        assertEquals("phone_brightness" to buildJsonObject { put("percent", 40) }, call("Set brightness to 40%"))
        assertEquals("phone_brightness" to buildJsonObject { put("percent", 100) }, call("turn the screen brightness to max"))
        assertEquals("phone_brightness" to buildJsonObject { put("percent", 25) }, call("brightness 25 percent"))
        assertEquals("phone_volume" to buildJsonObject { put("percent", 30); put("stream", "media") }, call("Set the volume to 30%"))
        assertEquals("phone_volume" to buildJsonObject { put("percent", 0); put("stream", "ring") }, call("set the ringer volume to minimum"))
        assertEquals("phone_volume" to buildJsonObject { put("percent", 80); put("stream", "alarm") }, call("alarm volume 80"))
        assertNull("over 100", call("set brightness to 140%"))
        assertNull("not a level: the model decides", call("turn the volume up a bit"))
    }

    @Test
    fun `the flashlight and settings pages`() {
        assertEquals(buildJsonObject { put("on", true) }, PhoneCommands.match("Turn on the flashlight")?.arguments)
        assertEquals(buildJsonObject { put("on", false) }, PhoneCommands.match("torch off!")?.arguments)
        assertEquals(buildJsonObject { put("page", "wifi") }, PhoneCommands.match("Open Wi-Fi settings")?.arguments)
        assertEquals(buildJsonObject { put("page", "notifications") }, PhoneCommands.match("show my notification settings")?.arguments)
    }

    @Test
    fun `Do Not Disturb, on and off, however it's put`() {
        for (text in listOf("Turn on do not disturb", "do not disturb on", "Switch DND on.", "turn do not disturb on please")) {
            assertEquals(text, ToolCall("phone_do_not_disturb", buildJsonObject { put("on", true) }), PhoneCommands.match(text))
        }
        assertEquals(ToolCall("phone_do_not_disturb", buildJsonObject { put("on", false) }), PhoneCommands.match("turn off dnd"))
        assertEquals(null, PhoneCommands.match("do not disturb me until noon"))
    }

    @Test
    fun `anything more than one whole command goes to the model`() {
        assertNull(PhoneCommands.match("set a timer for ten minutes and email Maya the agenda"))
        assertNull(PhoneCommands.match("Can you set a timer for my pasta?"))
        assertNull(PhoneCommands.match("How long should I set a timer for 2 eggs?"))
        assertNull(PhoneCommands.match("Set a timer for 3 days"))
        assertNull(PhoneCommands.match("turn on the lights"))
        assertNull(PhoneCommands.match("What's on my calendar today?"))
    }

    /** Saturday 2026-10-24, 2:30 PM. */
    private val now = LocalDateTime.of(2026, 10, 24, 14, 30, 15)

    private fun reminder(text: String, at: String) = ToolCall("phone_set_reminder", buildJsonObject { put("text", text); put("at", at) })

    @Test
    fun `reminders at a time, with the user's words and capitals (ADR-107)`() {
        assertEquals(reminder("call Mom", "2026-10-24T17:00"), PhoneCommands.match("Remind me at 5 pm to call Mom", now))
        assertEquals(reminder("call Mom", "2026-10-24T17:00"), PhoneCommands.match("remind me to call Mom at 5pm.", now))
        assertEquals(reminder("call the bank", "2026-10-25T09:00"), PhoneCommands.match("Remind me tomorrow at 9 am to call the bank", now))
        assertEquals(reminder("call the bank", "2026-10-25T09:30"), PhoneCommands.match("remind me to call the bank at 9:30 a.m. tomorrow", now))
        assertEquals(reminder("take out the trash", "2026-10-24T20:00"), PhoneCommands.match("Remind me tonight at 8 to take out the trash", now))
        assertEquals(reminder("look at the oven", "2026-10-24T18:15"), PhoneCommands.match("Please remind me to look at the oven at 18:15", now))
        assertEquals(reminder("put the cake in the oven", "2026-10-24T16:00"), PhoneCommands.match("remind me at 4 pm to put the cake in the oven", now))
        assertEquals(reminder("the dentist", "2026-10-25T08:00"), PhoneCommands.match("Set a reminder for the dentist at 8 am", now))
    }

    @Test
    fun `a time with no AM or PM is the next one, and a time that has passed today is tomorrow's`() {
        assertEquals("5 after 2:30 PM is 5 PM", reminder("call Mom", "2026-10-24T17:00"), PhoneCommands.match("remind me at 5 to call Mom", now))
        assertEquals("2 after 2:30 PM is 2 AM", reminder("check the bread", "2026-10-25T02:00"), PhoneCommands.match("remind me at 2 to check the bread", now))
        assertEquals(reminder("stretch", "2026-10-25T09:00"), PhoneCommands.match("remind me at 9 am to stretch", now))
        assertEquals("24-hour", reminder("stretch", "2026-10-25T09:00"), PhoneCommands.match("remind me at 09:00 to stretch", now))
        assertNull("today, but already past", PhoneCommands.match("remind me today at 9 am to stretch", now))
        assertNull("a day with no AM or PM is the model's", PhoneCommands.match("remind me tomorrow at 9 to stretch", now))
    }

    @Test
    fun `reminders in a while`() {
        assertEquals(reminder("check the oven", "2026-10-24T14:50"), PhoneCommands.match("Remind me in 20 minutes to check the oven", now))
        assertEquals(reminder("check the oven", "2026-10-24T15:30"), PhoneCommands.match("remind me to check the oven in an hour", now))
        assertEquals(reminder("move the car", "2026-10-24T15:00"), PhoneCommands.match("remind me in half an hour to move the car", now))
        assertNull("more than a day", PhoneCommands.match("remind me in 30 hours to move the car", now))
    }

    @Test
    fun `reminders that aren't one whole command go to the model`() {
        assertNull(PhoneCommands.match("Remind me on Friday to pay the rent", now))
        assertNull(PhoneCommands.match("Remind me to call Mom", now))
        assertNull(PhoneCommands.match("Remind me what a haiku is", now))
        assertNull(PhoneCommands.match("remind me at 5 pm", now))
        assertNull(PhoneCommands.match("Can you remind me at 5 pm to call Mom?", now))
    }

    @Test
    fun `listing and cancelling reminders`() {
        assertEquals(ToolCall("phone_list_reminders", buildJsonObject {}), PhoneCommands.match("What are my reminders?", now))
        assertEquals(ToolCall("phone_list_reminders", buildJsonObject {}), PhoneCommands.match("show my reminders", now))
        assertEquals(ToolCall("phone_cancel_reminder", buildJsonObject { put("words", "call Mom") }), PhoneCommands.match("Cancel my reminder to call Mom", now))
    }

    @Test
    fun `a number to call, a place to go, and an app to open`() {
        assertEquals(ToolCall("phone_call", buildJsonObject { put("number", "555-0123") }), PhoneCommands.match("Call 555-0123", now))
        assertEquals(ToolCall("phone_call", buildJsonObject { put("number", "+1 (555) 010-0199") }), PhoneCommands.match("dial +1 (555) 010-0199", now))
        assertNull("a person's number comes from contacts, through the model", PhoneCommands.match("Call Mom", now))
        assertEquals(ToolCall("phone_navigate", buildJsonObject { put("destination", "San Francisco Airport") }), PhoneCommands.match("Navigate to San Francisco Airport", now))
        assertEquals(ToolCall("phone_navigate", buildJsonObject { put("destination", "the nearest pharmacy") }), PhoneCommands.match("Get directions to the nearest pharmacy", now))
        assertNull(PhoneCommands.match("Navigate to the airport and text Maya", now))
        val installed = setOf("spotify", "google maps")
        fun open(text: String) = PhoneCommands.match(text, now) { it.lowercase() in installed }
        assertEquals(ToolCall("phone_open_app", buildJsonObject { put("name", "Spotify") }), open("Open Spotify"))
        assertEquals(ToolCall("phone_open_app", buildJsonObject { put("name", "Google Maps") }), open("launch the Google Maps"))
        assertNull("no such app: the model", open("Open the door"))
        assertNull("the word app is app control's (ADR-098)", open("Open the Spotify app"))
        assertNull(open("Open Spotify and play some jazz"))
        assertEquals("settings pages come first", "phone_open_settings", open("open Wi-Fi settings")?.name)
        assertEquals("timers come first", "phone_set_timer", open("start a timer for 5 minutes")?.name)
    }
}
