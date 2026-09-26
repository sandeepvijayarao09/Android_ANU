package io.github.sandeepvijayarao09.anu.connector.messages

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Captures messaging conversations from notifications (ARCHITECTURE §9, ADR-031). Android starts it
 * only after the user turns on notification access for Anu in system settings. Non-messaging
 * notifications are ignored and never stored, and so are Anu's own.
 */
class AnuNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.forEach(::capture)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = MessagingHub.store.detachReply(keyOf(sbn))

    private fun capture(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        NotificationParser.parse(this, sbn.notification, sbn.packageName, label(sbn.packageName), keyOf(sbn))
            ?.let(MessagingHub.store::update)
    }

    private fun label(app: String): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(app, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(app, 0)
        }
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(app)

    companion object {
        /** A conversation is one shortcut, or failing that one notification tag or id, within an app. */
        fun keyOf(sbn: StatusBarNotification): String =
            "${sbn.packageName}:${sbn.notification.shortcutId ?: sbn.tag ?: sbn.id}"

        fun isEnabled(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun component(context: Context) = ComponentName(context, AnuNotificationListener::class.java)
    }
}
