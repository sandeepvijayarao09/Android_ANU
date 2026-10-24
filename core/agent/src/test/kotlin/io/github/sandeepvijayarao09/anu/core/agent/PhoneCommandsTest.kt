package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
