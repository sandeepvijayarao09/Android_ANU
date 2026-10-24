package io.github.sandeepvijayarao09.anu.connector.apps

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * App control's accessibility service (ADR-098). The user turns it on in Android's settings; it acts only while an app
 * task runs. Between tasks it asks Android for no events at all, and during one only for the task's app, so it hears
 * nothing from other apps. Events only time when a screen has settled: none is kept, logged or shown to a model.
 */
class AnuAccessibilityService : AccessibilityService() {
    /** When the task's app last changed its screen, in uptime milliseconds. */
    @Volatile
    var lastChangeAt: Long = 0L
        private set

    internal val pill by lazy { ProgressPill(this) }

    override fun onServiceConnected() {
        listenTo(null)
        instance = this
    }

    /** Asks Android for the screen changes of [packageName] only, or for none when it's null. */
    internal fun listenTo(packageName: String?) {
        serviceInfo = serviceInfo?.apply {
            eventTypes = if (packageName == null) 0 else EVENTS
            packageNames = packageName?.let { arrayOf(it) }
        }
        lastChangeAt = SystemClock.uptimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastChangeAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        pill.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** The running service, while the user has app control on. */
        @Volatile
        var instance: AnuAccessibilityService? = null
            private set

        private const val EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
    }
}
