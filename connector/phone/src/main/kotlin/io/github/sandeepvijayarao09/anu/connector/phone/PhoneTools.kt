package io.github.sandeepvijayarao09.anu.connector.phone

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.Settings
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** What a phone control did, in its own words: the answer to a command that needs no model (ADR-076). */
@Serializable
data class Done(val said: String)

@Serializable
data class TimerInput(@Description("How long the timer runs, in seconds") val seconds: Int)

@Serializable
data class AlarmInput(@Description("Hour, 0 to 23") val hour: Int, @Description("Minute, 0 to 59") val minute: Int = 0)

@Serializable
data class FlashlightInput(@Description("true to turn the flashlight on, false to turn it off") val on: Boolean)

@Serializable
data class DoNotDisturbInput(@Description("true to turn Do Not Disturb on, false to turn it off") val on: Boolean)

@Serializable
data class SettingsInput(
    @Description("One of: wifi, bluetooth, battery, display, sound, location, notifications, accessibility") val page: String,
)

/**
 * Phone controls (G6, ADR-076). Each is a broker tool with a risk tier, so a command runs under the same policy
 * whether the model asked or the user's words matched one exactly. They touch no personal data and return none.
 */
abstract class PhoneTool<I : Any>(protected val context: Context) : Tool<I, Done> {
    override val output: KSerializer<Done> = Done.serializer()
    override val dataClasses: Set<DataClass> = emptySet()
    override val untrustedContent: UntrustedContent? = null

    /** Starts [intent] in a new task, or says which kind of app is missing. */
    protected fun start(intent: Intent, missing: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            error("no $missing on this phone can do that")
        }
    }
}

/** A countdown in the phone's clock app. Changes only the user's own phone, and the clock app can cancel it. */
class SetTimerTool(context: Context) : PhoneTool<TimerInput>(context) {
    override val name = "phone_set_timer"
    override val description = "Start a countdown timer in the phone's clock app"
    override val input: KSerializer<TimerInput> = TimerInput.serializer()
    override fun risk(input: TimerInput) = RiskTier.WRITE_PRIVATE

    override fun describe(input: TimerInput): String {
        require(input.seconds in 1..86_400) { "a timer runs from 1 second to 24 hours" }
        return "Set a timer for ${duration(input.seconds)}"
    }

    override suspend fun invoke(input: TimerInput, context: ToolContext): Done {
        start(
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, input.seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "clock app",
        )
        return Done("Timer set for ${duration(input.seconds)}.")
    }
}

/** An alarm in the phone's clock app. */
class SetAlarmTool(context: Context) : PhoneTool<AlarmInput>(context) {
    override val name = "phone_set_alarm"
    override val description = "Set an alarm in the phone's clock app"
    override val input: KSerializer<AlarmInput> = AlarmInput.serializer()
    override fun risk(input: AlarmInput) = RiskTier.WRITE_PRIVATE

    override fun describe(input: AlarmInput): String {
        require(input.hour in 0..23 && input.minute in 0..59) { "an alarm's time is 0:00 to 23:59" }
        return "Set an alarm for ${clock(input.hour, input.minute)}"
    }

    override suspend fun invoke(input: AlarmInput, context: ToolContext): Done {
        start(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, input.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, input.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "clock app",
        )
        return Done("Alarm set for ${clock(input.hour, input.minute)}.")
    }
}

/** The camera's flash as a torch. No permission is needed for the torch. */
class FlashlightTool(context: Context) : PhoneTool<FlashlightInput>(context) {
    override val name = "phone_flashlight"
    override val description = "Turn the phone's flashlight on or off"
    override val input: KSerializer<FlashlightInput> = FlashlightInput.serializer()
    override fun risk(input: FlashlightInput) = RiskTier.WRITE_PRIVATE
    override fun describe(input: FlashlightInput) = if (input.on) "Turn on the flashlight" else "Turn off the flashlight"

    override suspend fun invoke(input: FlashlightInput, context: ToolContext): Done {
        val cameras = this.context.getSystemService(CameraManager::class.java)
        val flash = cameras.cameraIdList.firstOrNull { id ->
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: error("this phone has no flashlight")
        cameras.setTorchMode(flash, input.on)
        return Done(if (input.on) "Flashlight on." else "Flashlight off.")
    }
}

/**
 * Do Not Disturb, as the user has set it up: priority only. Android asks the user to allow Anu once, on a page of its
 * own; until then the tool opens that page and fails, changing nothing, so its receipt never claims it did.
 */
class DoNotDisturbTool(context: Context) : PhoneTool<DoNotDisturbInput>(context) {
    override val name = "phone_do_not_disturb"
    override val description = "Turn Do Not Disturb on or off"
    override val input: KSerializer<DoNotDisturbInput> = DoNotDisturbInput.serializer()
    override fun risk(input: DoNotDisturbInput) = RiskTier.WRITE_PRIVATE
    override fun describe(input: DoNotDisturbInput) = if (input.on) "Turn on Do Not Disturb" else "Turn off Do Not Disturb"

    override suspend fun invoke(input: DoNotDisturbInput, context: ToolContext): Done {
        val notifications = this.context.getSystemService(NotificationManager::class.java)
        if (!notifications.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "Settings app")
            error("Do Not Disturb needs your OK first. Allow Anu on the page that opened, then ask again")
        }
        notifications.setInterruptionFilter(if (input.on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Done(if (input.on) "Do Not Disturb is on." else "Do Not Disturb is off.")
    }
}

/** A page of the phone's Settings app. It only opens the page; nothing changes until the user does. */
class OpenSettingsTool(context: Context) : PhoneTool<SettingsInput>(context) {
    override val name = "phone_open_settings"
    override val description = "Open a page of the phone's Settings app"
    override val input: KSerializer<SettingsInput> = SettingsInput.serializer()
    override fun risk(input: SettingsInput) = RiskTier.READ

    override fun describe(input: SettingsInput): String {
        require(input.page in PAGES) { "the pages are ${PAGES.keys.joinToString()}" }
        return "Open ${PAGES.getValue(input.page).first} settings"
    }

    override suspend fun invoke(input: SettingsInput, context: ToolContext): Done {
        val (label, action) = PAGES.getValue(input.page)
        start(Intent(action), "Settings app")
        return Done("Opened $label settings.")
    }

    private companion object {
        val PAGES = mapOf(
            "wifi" to ("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS),
            "bluetooth" to ("Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS),
            "battery" to ("battery" to Intent.ACTION_POWER_USAGE_SUMMARY),
            "display" to ("display" to Settings.ACTION_DISPLAY_SETTINGS),
            "sound" to ("sound" to Settings.ACTION_SOUND_SETTINGS),
            "location" to ("location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            "notifications" to ("notification" to "android.settings.NOTIFICATION_SETTINGS"),
            "accessibility" to ("accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }
}

/** "10 minutes", "1 hour 30 minutes", "45 seconds". */
internal fun duration(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = seconds % 60
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { plural(it, "hour") },
        minutes.takeIf { it > 0 }?.let { plural(it, "minute") },
        rest.takeIf { it > 0 }?.let { plural(it, "second") },
    ).joinToString(" ")
}

/** "7:00 AM", "6:45 PM", "12:00 AM". */
internal fun clock(hour: Int, minute: Int): String {
    val half = if (hour < 12) "AM" else "PM"
    val twelve = (hour % 12).let { if (it == 0) 12 else it }
    return "$twelve:${minute.toString().padStart(2, '0')} $half"
}

private fun plural(count: Int, unit: String) = if (count == 1) "1 $unit" else "$count ${unit}s"
