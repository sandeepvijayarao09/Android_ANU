package io.github.sandeepvijayarao09.anu.connector.messages

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import java.time.Instant

/**
 * Turns a messaging notification into a [ConversationSnapshot] (ADR-031). Only `MessagingStyle`
 * notifications count. Anything else returns null and is never stored.
 */
object NotificationParser {

    /** What Anu shows for a message Android hid from it. */
    const val HIDDEN = "(Android hid this message from Anu. It may contain a verification code.)"

    /**
     * The stand-in text Android 15+ gives notification listeners in place of sensitive content, such
     * as one-time codes, in the phone's language. Null on versions that don't hide anything.
     */
    private val hiddenByAndroid: String? by lazy {
        val system = Resources.getSystem()
        val id = system.getIdentifier("redacted_notification_message", "string", "android")
        if (id == 0) null else runCatching { system.getString(id) }.getOrNull()
    }

    fun parse(context: Context, notification: Notification, app: String, appLabel: String, key: String): ConversationSnapshot? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return null
        val me = style.user
        // Never the user's own name, which Android puts here when the newest message is theirs.
        val shownTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it != me.name?.toString() }
        val lines = style.messages.mapNotNull { message ->
            val text = message.text?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (text == hiddenByAndroid) {
                // Android marks its stand-in as the user's own message, but it was received. The emulator
                // showed Anu saying "you said" for a code it was never shown.
                return@mapNotNull ChatLine(
                    sender = style.conversationTitle?.toString() ?: shownTitle ?: "Someone",
                    text = HIDDEN,
                    at = Instant.ofEpochMilli(message.timestamp),
                    fromMe = false,
                )
            }
            val person = message.person
            val fromMe = person == null || (me.key != null && person.key == me.key) || person.name == me.name
            ChatLine(
                sender = if (fromMe) "You" else person.name?.toString() ?: "Someone",
                text = text,
                at = Instant.ofEpochMilli(message.timestamp),
                fromMe = fromMe,
            )
        }
        if (lines.isEmpty()) return null
        // Not EXTRA_TITLE first: Android sets it to the latest named sender, which is the user themself
        // when an app names the user on their own messages. The emulator test caught this.
        val title = style.conversationTitle?.toString()
            ?: lines.lastOrNull { !it.fromMe }?.sender
            ?: shownTitle
            ?: appLabel
        return ConversationSnapshot(
            key = key,
            app = app,
            appLabel = appLabel,
            title = title,
            isGroup = style.isGroupConversation,
            lines = lines,
            reply = replySender(context, notification),
        )
    }

    /** The notification's free-text reply action, as Android Auto and Wear use it, or null if it has none. */
    fun replySender(context: Context, notification: Notification): ReplySender? {
        val action = (0 until NotificationCompat.getActionCount(notification))
            .mapNotNull { NotificationCompat.getAction(notification, it) }
            .filter { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }
            .let { candidates -> candidates.firstOrNull { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY } ?: candidates.firstOrNull() }
            ?: return null
        val inputs = action.remoteInputs ?: return null
        val target = action.actionIntent ?: return null
        return ReplySender { text ->
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            target.send(context, 0, intent)
        }
    }
}
