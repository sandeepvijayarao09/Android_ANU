package io.github.sandeepvijayarao09.anu.connector.phone

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A reminder the user set (ADR-107): what to be reminded of, and when, on the phone's clock. */
data class Reminder(val id: Long, val text: String, val atMillis: Long)

/** Where reminders are kept, so they come back after a reboot. `core:data` keeps them in the encrypted database. */
interface ReminderStore {
    suspend fun add(text: String, atMillis: Long, createdAtMillis: Long): Reminder

    /** Every reminder not yet shown, soonest first. */
    suspend fun all(): List<Reminder>

    suspend fun remove(id: Long)
}

/** The application, which holds the reminders, so the receivers can reach them when an alarm fires or the phone starts. */
interface ReminderHost {
    val reminders: Reminders
}

/**
 * Timed reminders (ADR-107): each is a notification at a local time, set with Android's `AlarmManager`. With exact alarms
 * allowed (always in the `personal` build, which may use `USE_EXACT_ALARM`) it comes on the minute, even in Doze; without,
 * Android may hold it for up to [WINDOW_MINUTES] minutes, and the receipt says so. Alarms don't survive a reboot, so the
 * store keeps every reminder until it's shown, and [restore] sets them again when the phone starts.
 */
class Reminders(private val context: Context, private val store: ReminderStore, private val clock: () -> ZonedDateTime) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    fun now(): ZonedDateTime = clock()

    /** Whether Android lets Anu post notifications, without which a reminder can't show. */
    fun canNotify(): Boolean = notifications.areNotificationsEnabled()

    /** Saves and schedules a reminder; true when it will come on the minute. */
    suspend fun set(text: String, at: LocalDateTime): Pair<Reminder, Boolean> {
        val now = clock()
        val reminder = store.add(text, at.atZone(now.zone).toInstant().toEpochMilli(), now.toInstant().toEpochMilli())
        return reminder to schedule(reminder)
    }

    suspend fun upcoming(): List<Reminder> = store.all()

    suspend fun cancel(reminder: Reminder) {
        alarms.cancel(pending(reminder))
        store.remove(reminder.id)
    }

    /** A reminder's alarm fired: it's shown, and kept no longer. */
    suspend fun fired(id: Long, text: String) {
        show(id, text)
        store.remove(id)
    }

    /** After a reboot or an update, which clear Android's alarms: due ones show now, the rest are set again. */
    suspend fun restore() {
        val now = clock().toInstant().toEpochMilli()
        for (reminder in store.all()) {
            if (reminder.atMillis <= now) fired(reminder.id, reminder.text) else schedule(reminder)
        }
    }

    /** Opens Android's page for Anu's notifications, where the user can allow them. */
    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun schedule(reminder: Reminder): Boolean {
        val pending = pending(reminder)
        if (alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, pending)
                return true
            } catch (_: SecurityException) {
                // Taken back between the check and the call: set it inexactly below.
            }
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, reminder.atMillis, WINDOW_MINUTES * 60_000L, pending)
        return false
    }

    /** One alarm per reminder: the data URI keeps them apart, since Android compares intents without their extras. */
    private fun pending(reminder: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("anu-reminder:${reminder.id}"))
            .putExtra(EXTRA_ID, reminder.id)
            .putExtra(EXTRA_TEXT, reminder.text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun show(id: Long, text: String) {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(text.replaceFirstChar { it.titlecase(Locale.getDefault()) })
            .setContentText("Reminder")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notifications.notify(NOTIFICATION_TAG, id.toInt(), notification)
    }

    companion object {
        const val WINDOW_MINUTES = 10
        internal const val ACTION_FIRE = "io.github.sandeepvijayarao09.anu.REMINDER"
        internal const val EXTRA_ID = "id"
        internal const val EXTRA_TEXT = "text"
        private const val CHANNEL = "reminders"
        private const val NOTIFICATION_TAG = "reminder"
    }
}

/** A reminder's alarm. Only Anu's own pending intent reaches it (not exported). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION_FIRE) return
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1).takeIf { it >= 0 } ?: return
        val text = intent.getStringExtra(Reminders.EXTRA_TEXT) ?: return
        val reminders = (context.applicationContext as ReminderHost).reminders
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reminders.fired(id, text)
            } finally {
                done.finish()
            }
        }
    }
}

/** The phone started, or Anu was updated: both clear Android's alarms, so the saved reminders are set again. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val reminders = (context.applicationContext as ReminderHost).reminders
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reminders.restore()
            } finally {
                done.finish()
            }
        }
    }
}

@Serializable
data class ReminderInput(
    @Description("What to remind the user of, in their words, such as call Mom") val text: String,
    @Description("When, as local ISO 8601 such as 2026-10-24T17:00; a time alone such as 17:00 means its next time") val at: String,
)

@Serializable
data class ListRemindersInput(@Description("How many to list, soonest first") val limit: Int = 10)

@Serializable
data class CancelReminderInput(@Description("Words from the reminder to cancel, such as call Mom") val words: String)

/**
 * A reminder at a local time (ADR-107): a notification on the user's own phone, which they can clear. It holds their own
 * words, so its result is personal; it changes nothing outside the phone, so it runs at once unless the turn is tainted.
 */
class SetReminderTool(context: Context, private val reminders: Reminders) : PhoneTool<ReminderInput>(context) {
    override val name = "phone_set_reminder"
    override val description = "Set a reminder: a notification on the phone at a local time"
    override val input: KSerializer<ReminderInput> = ReminderInput.serializer()
    override val dataClasses: Set<DataClass> = setOf(DataClass.PERSONAL)
    override fun risk(input: ReminderInput) = RiskTier.WRITE_PRIVATE

    override fun describe(input: ReminderInput): String {
        val now = reminders.now().toLocalDateTime()
        val at = reminderTime(input.at, now)
        require(input.text.isNotBlank()) { "say what to be reminded of" }
        require(input.text.length <= MAX_TEXT) { "a reminder is at most $MAX_TEXT characters" }
        require(at.isAfter(now)) { "${whenWords(at, now.toLocalDate())} has passed; give a time from now on" }
        require(at.isBefore(now.plusYears(1))) { "a reminder is at most a year away" }
        return "Remind you “${input.text.trim()}” ${whenWords(at, now.toLocalDate())}"
    }

    override suspend fun invoke(input: ReminderInput, context: ToolContext): Done {
        if (!reminders.canNotify()) {
            reminders.openNotificationSettings()
            error("Reminders show as notifications, which are off for Anu. Allow them on the page that opened, then ask again")
        }
        val now = reminders.now().toLocalDateTime()
        val at = reminderTime(input.at, now)
        val (_, exact) = reminders.set(input.text.trim(), at)
        return Done(reminderSaid(input.text.trim(), whenWords(at, now.toLocalDate()), exact))
    }

    private companion object {
        const val MAX_TEXT = 200
    }
}

/** The reminders still to come. They're the user's own words, so the result is personal. */
class ListRemindersTool(context: Context, private val reminders: Reminders) : PhoneTool<ListRemindersInput>(context) {
    override val name = "phone_list_reminders"
    override val description = "List the reminders the user has set, soonest first"
    override val input: KSerializer<ListRemindersInput> = ListRemindersInput.serializer()
    override val dataClasses: Set<DataClass> = setOf(DataClass.PERSONAL)
    override fun risk(input: ListRemindersInput) = RiskTier.READ
    override fun describe(input: ListRemindersInput) = "List your reminders"

    override suspend fun invoke(input: ListRemindersInput, context: ToolContext): Done {
        val now = reminders.now()
        val all = reminders.upcoming()
        if (all.isEmpty()) return Done("You have no reminders set.")
        val listed = all.take(input.limit.coerceIn(1, 20)).joinToString("; ") { reminder ->
            "“${reminder.text}” ${whenWords(localTime(reminder, now), now.toLocalDate())}"
        }
        val count = if (all.size == 1) "1 reminder" else "${all.size} reminders"
        return Done("$count: $listed.")
    }
}

/**
 * Cancels a reminder found by its words, like a note by its title: the exact text, else the one that holds every word.
 * It deletes the user's own data with no undo, so it asks every time, naming the words.
 */
class CancelReminderTool(context: Context, private val reminders: Reminders) : PhoneTool<CancelReminderInput>(context) {
    override val name = "phone_cancel_reminder"
    override val description = "Cancel one of the user's reminders, found by its words"
    override val input: KSerializer<CancelReminderInput> = CancelReminderInput.serializer()
    override val dataClasses: Set<DataClass> = setOf(DataClass.PERSONAL)
    override fun risk(input: CancelReminderInput) = RiskTier.DESTRUCTIVE

    override fun describe(input: CancelReminderInput): String {
        require(input.words.isNotBlank()) { "say which reminder to cancel" }
        return "Cancel your reminder “${input.words.trim()}”"
    }

    override suspend fun invoke(input: CancelReminderInput, context: ToolContext): Done {
        val now = reminders.now()
        val found = matchReminders(input.words, reminders.upcoming())
        when {
            found.isEmpty() -> error("no reminder matches “${input.words.trim()}”")
            found.size > 1 -> error("“${input.words.trim()}” could be ${found.joinToString(" or ") { "“${it.text}”" }}; say which")
        }
        val reminder = found.single()
        reminders.cancel(reminder)
        return Done("Cancelled your reminder “${reminder.text}” ${whenWords(localTime(reminder, now), now.toLocalDate())}.")
    }
}

private fun localTime(reminder: Reminder, now: ZonedDateTime): LocalDateTime =
    java.time.Instant.ofEpochMilli(reminder.atMillis).atZone(now.zone).toLocalDateTime()

/**
 * The local time [at] means: an ISO date and time, or a time of day alone, which is its next time from [now].
 * Anything else fails with the form to use, so the model can correct it.
 */
internal fun reminderTime(at: String, now: LocalDateTime): LocalDateTime {
    val text = at.trim()
    try {
        return LocalDateTime.parse(text).withSecond(0).withNano(0)
    } catch (_: DateTimeParseException) {
    }
    val time = try {
        LocalTime.parse(text)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("write the time as local ISO 8601, such as 2026-10-24T17:00, or a time alone, such as 17:00")
    }
    val today = now.toLocalDate().atTime(time.withSecond(0).withNano(0))
    return if (today.isAfter(now)) today else today.plusDays(1)
}

/** "today at 5:00 PM", "tomorrow at 9:30 AM", "on Saturday, October 31 at 8:00 AM". */
internal fun whenWords(at: LocalDateTime, today: LocalDate): String {
    val time = clock(at.hour, at.minute)
    val day = at.toLocalDate()
    return when {
        day == today -> "today at $time"
        day == today.plusDays(1) -> "tomorrow at $time"
        day.year == today.year -> "on ${DAY.format(day)} at $time"
        else -> "on ${DAY_YEAR.format(day)} at $time"
    }
}

/** What a set reminder says: when it comes, and whether it may be late. */
internal fun reminderSaid(text: String, whenWords: String, exact: Boolean): String {
    val said = "I'll remind you “$text” $whenWords."
    if (exact) return said
    return "$said It may come up to ${Reminders.WINDOW_MINUTES} minutes late; allow Alarms & reminders for Anu in Settings to have it on the minute."
}

/** The reminders [words] mean: the one whose text is exactly them, else every one that holds all of them. */
internal fun matchReminders(words: String, reminders: List<Reminder>): List<Reminder> {
    val query = words.trim().lowercase().removePrefix("to ").removePrefix("about ")
    reminders.filter { it.text.lowercase() == query }.takeIf { it.isNotEmpty() }?.let { return it }
    val parts = query.split(Regex("\\s+")).filter(String::isNotBlank)
    if (parts.isEmpty()) return emptyList()
    return reminders.filter { reminder -> reminder.text.lowercase().let { text -> parts.all(text::contains) } }
}

private val DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
private val DAY_YEAR = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
