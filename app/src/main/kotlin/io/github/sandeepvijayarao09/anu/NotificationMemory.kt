package io.github.sandeepvijayarao09.anu

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.sandeepvijayarao09.anu.connector.messages.MessagingHub
import io.github.sandeepvijayarao09.anu.connector.messages.PostedNotification
import io.github.sandeepvijayarao09.anu.core.memory.MemoryPipeline
import io.github.sandeepvijayarao09.anu.feature.settings.NotificationLearning
import io.github.sandeepvijayarao09.anu.feature.settings.NotifyingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Which apps memory learns from (ADR-127), in the app's settings: the phone's apps with a launcher icon, and those that
 * posted a notification, listed by package and name only, never what they said. Each starts off until the user turns
 * it on. On the Pixel, the list of apps that had notified held one app, so choosing waited on apps posting first.
 */
class PrefsNotificationLearning(
    private val prefs: SharedPreferences,
    /** The phone's apps with a launcher icon, as package to name. Asked off the main thread. */
    private val installed: () -> Map<String, String> = { emptyMap() },
    private val ownPackage: String = "",
) : NotificationLearning {
    @Volatile
    private var launchable: Map<String, String> = emptyMap()

    private val state = MutableStateFlow(read())

    override val apps: StateFlow<List<NotifyingApp>> = state.asStateFlow()

    fun isLearning(packageName: String): Boolean = packageName in prefs.getStringSet(LEARNING, emptySet()).orEmpty()

    /** An app posted a notification: list it, if it isn't already. */
    fun seen(packageName: String, label: String) {
        if (state.value.any { it.packageName == packageName && it.label == label && it.notified }) return
        synchronized(this) {
            val known = JSONObject(prefs.getString(SEEN, "{}") ?: "{}").put(packageName, label)
            prefs.edit().putString(SEEN, known.toString()).apply()
            state.value = read()
        }
    }

    override fun setLearning(packageName: String, on: Boolean) = synchronized(this) {
        val learning = prefs.getStringSet(LEARNING, emptySet()).orEmpty()
        prefs.edit().putStringSet(LEARNING, if (on) learning + packageName else learning - packageName).apply()
        state.value = read()
    }

    override suspend fun refresh() {
        launchable = withContext(Dispatchers.IO) { runCatching(installed).getOrDefault(emptyMap()) }
        state.value = read()
    }

    private fun read(): List<NotifyingApp> {
        val known = JSONObject(prefs.getString(SEEN, "{}") ?: "{}")
        val seen = known.keys().asSequence().associateWith { known.getString(it) }
        val learning = prefs.getStringSet(LEARNING, emptySet()).orEmpty()
        return (launchable + seen).filterKeys { it != ownPackage }
            .map { (pkg, label) -> NotifyingApp(pkg, label, pkg in learning, notified = pkg in seen) }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        const val SEEN = "notifying_apps"
        const val LEARNING = "learning_apps"
    }
}

/**
 * Sends the chosen apps' notifications to memory (ADR-127), in builds that offer it. Each one is captured at once,
 * and learning waits until the phone is idle and charging (ADR-064). Debug builds log the app and a count, never
 * what a notification said.
 */
object NotificationMemory {
    /** [memory] is asked for off the main thread: making it opens the database. */
    fun install(context: Context, learning: PrefsNotificationLearning, memory: () -> MemoryPipeline, scope: CoroutineScope) {
        val app = context.applicationContext
        MessagingHub.onPosted = { posted: PostedNotification ->
            learning.seen(posted.app, posted.appLabel)
            if (learning.isLearning(posted.app)) {
                scope.launch {
                    val event = runCatching { memory().captureNotification(posted.app, posted.appLabel, posted.conversation, posted.lines, posted.at) }.getOrNull()
                    if (BuildConfig.DEBUG) Log.d("Anu", "Notification memory: ${posted.app}, ${posted.lines.size} lines, ${if (event != null) "kept in event $event" else "nothing new"}")
                    if (event != null) MemoryLearning.whenIdle(app)
                }
            }
        }
    }
}
