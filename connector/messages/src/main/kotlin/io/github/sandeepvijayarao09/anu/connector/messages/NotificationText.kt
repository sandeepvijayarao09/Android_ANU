package io.github.sandeepvijayarao09.anu.connector.messages

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.time.Instant

/**
 * What one notification said, for memory to learn from (ADR-127): its app, who or what it's from, and its lines.
 * Other people and apps wrote them, so they are untrusted data (P4).
 */
data class PostedNotification(
    val app: String,
    val appLabel: String,
    /** The chat or sender, else the notification's title, else the app. A day's notifications from one gather together. */
    val conversation: String,
    /** "Maya Chen: See you Friday", or a plain notification's title and text. */
    val lines: List<String>,
    val at: Instant,
)

/**
 * Reads any app's notification as words (ADR-127). It skips what holds nothing lasting or nothing of the user's:
 * ongoing and foreground-service notifications, progress, media, calls, alarms, navigation, group summaries, and
 * notifications the app marked secret on the lock screen. A message Android hid, such as a one-time code, is
 * left out too (I1).
 */
object NotificationText {

    fun read(sbn: StatusBarNotification, appLabel: String): PostedNotification? {
        val notification = sbn.notification
        if (sbn.isOngoing || notification.flags and SKIPPED_FLAGS != 0) return null
        if (notification.category in SKIPPED_CATEGORIES) return null
        if (notification.visibility == Notification.VISIBILITY_SECRET) return null
        val extras = notification.extras
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0) return null
        val at = Instant.ofEpochMilli(sbn.postTime)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf(String::isNotEmpty)

        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.let { style ->
            val me = style.user
            val lines = style.messages.mapNotNull { message ->
                val text = message.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != NotificationParser.hiddenByAndroid } ?: return@mapNotNull null
                val person = message.person
                val fromMe = person == null || (me.key != null && person.key == me.key) || person.name == me.name
                "${if (fromMe) "You" else person?.name?.toString() ?: "Someone"}: $text"
            }
            if (lines.isEmpty()) return null
            val conversation = style.conversationTitle?.toString() ?: title?.takeIf { it != me.name?.toString() } ?: appLabel
            return PostedNotification(sbn.packageName, appLabel, conversation, lines, at)
        }

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != NotificationParser.hiddenByAndroid }
        val inbox = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
            .mapNotNull { line -> line?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != NotificationParser.hiddenByAndroid } }
        val lines = when {
            inbox.isNotEmpty() -> inbox
            text != null -> listOf(if (title != null) "$title: $text" else text)
            else -> return null
        }
        return PostedNotification(sbn.packageName, appLabel, title ?: appLabel, lines, at)
    }

    private const val SKIPPED_FLAGS = Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT

    /** `missed_call` and `location_sharing` are Android 13's names, spelled out for Android 12. */
    private val SKIPPED_CATEGORIES = setOf(
        Notification.CATEGORY_ALARM, Notification.CATEGORY_CALL, "missed_call", Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_TRANSPORT, "location_sharing", Notification.CATEGORY_STATUS,
    )
}
