package io.github.sandeepvijayarao09.anu

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.feature.home.ChatUiState
import io.github.sandeepvijayarao09.anu.feature.home.ChatViewModel
import io.github.sandeepvijayarao09.anu.feature.home.LiveBehind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What live talk is doing, for its notification and its control over other apps (ADR-108). */
internal enum class LivePhase { LISTENING, THINKING, SPEAKING, WAITING }

internal data class LiveStatus(val phase: LivePhase, val heard: String = "", val answer: String = "", val muted: Boolean = false)

/** Live talk's status from the Talk page's state, or null when live talk is off. */
internal fun liveStatus(state: ChatUiState): LiveStatus? {
    if (!state.live) return null
    val answer = state.bubbles.lastOrNull()?.takeIf { !it.fromUser }?.text.orEmpty()
    val phase = when {
        state.approval != null || state.consentFor != null -> LivePhase.WAITING
        state.listening -> LivePhase.LISTENING
        state.speaking -> LivePhase.SPEAKING
        state.busy -> LivePhase.THINKING
        // Between an answer and the next listen.
        else -> LivePhase.LISTENING
    }
    return LiveStatus(phase, state.heard.trim(), answer.trim(), state.muted)
}

/** The words of live talk's notification and pill: a heading, one line, and what TalkBack reads. */
internal data class LiveWords(val heading: String, val line: String, val description: String)

internal fun liveWords(status: LiveStatus): LiveWords {
    val (phase, line) = when (status.phase) {
        LivePhase.LISTENING -> "Listening" to status.heard.ifEmpty { "Say something, or tap Stop" }
        LivePhase.THINKING -> "Thinking" to (status.heard.takeIf { it.isNotEmpty() }?.let { "“$it”" } ?: "Working on it")
        LivePhase.SPEAKING -> (if (status.muted) "Answering" else "Speaking") to status.answer.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifEmpty { "Answering you" }
        LivePhase.WAITING -> "Waiting for you" to "Open Anu to approve or decline"
    }
    return LiveWords("Anu · $phase", line, "Live talk with Anu: ${phase.lowercase()}. $line")
}

/**
 * Whether live talk can go on behind another app (ADR-108), or why not: only while its microphone service runs, and only
 * with something the user can see and stop it from, the pill over other apps or the service's notification.
 */
internal fun liveBlocked(serviceRunning: Boolean, pill: Boolean, notifications: Boolean): String? = when {
    !serviceRunning -> LIVE_LEFT
    pill || notifications -> null
    else -> LIVE_NEEDS_NOTIFICATIONS
}

internal const val LIVE_LEFT = "Live talk ended when you left Anu."
internal const val LIVE_NEEDS_NOTIFICATIONS = "Live talk ended when you left Anu. Allow Anu's notifications to keep talking in other apps."

/** Live talk's control over other apps; only the personal build has one, through app control (ADR-108). */
internal interface FloatingControl {
    val available: Boolean

    fun show(words: LiveWords, onOpen: () -> Unit, onStop: () -> Unit)

    fun hide()
}

/**
 * Live talk beyond the Talk page (ADR-108). While the Talk page's live talk is on, [LiveTalkService] holds the
 * microphone as a foreground service with its notification, started while Anu is on screen, as Android requires, so
 * live talk can go on when the user switches apps. Stop, in the notification or the pill, ends it.
 */
class LiveTalk(private val context: Context) : LiveBehind {
    private val now = MutableStateFlow<LiveStatus?>(null)
    internal val status: StateFlow<LiveStatus?> = now.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether live talk is on, for "Hey Anu" to give way and for models to stay loaded behind other apps. */
    val on: StateFlow<Boolean> = now.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    @Volatile internal var serviceRunning = false
    private var chat: ChatViewModel? = null

    /** Follows the Talk page's [chat] for as long as it lives. */
    fun attach(chat: ChatViewModel) {
        this.chat = chat
        chat.viewModelScope.launch { chat.state.map(::liveStatus).distinctUntilChanged().collect(::update) }
        chat.addCloseable {
            if (this.chat === chat) this.chat = null
            update(null)
        }
    }

    override fun blocked(): String? = liveBlocked(
        serviceRunning,
        pill = Flavor.floatingControl?.available == true,
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    /** Stop, from the notification or the pill. */
    internal fun stop() {
        if (BuildConfig.DEBUG) Log.d("Anu", "Live talk: ended by Stop")
        chat?.endLive() ?: update(null)
    }

    private fun update(status: LiveStatus?) {
        val was = now.value
        now.value = status
        when {
            was == null && status != null -> start()
            status == null -> context.stopService(Intent(context, LiveTalkService::class.java))
        }
    }

    private fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        // Throws when Android doesn't allow it now; live talk then ends when the user leaves Anu, as before.
        runCatching { context.startForegroundService(Intent(context, LiveTalkService::class.java)) }
            .onFailure { if (BuildConfig.DEBUG) Log.d("Anu", "Live talk: service not started, ${it.javaClass.simpleName}") }
    }
}

/**
 * Live talk's microphone while the user is in another app (ADR-108). It's a foreground service of the microphone type
 * with a notification that says what Anu is doing, with End live talk; in the personal build, with app control on, a
 * pill at the bottom of other apps shows the same. It never listens by itself: the Talk page's live talk listens, and
 * this only keeps Android from silencing it, so nothing is heard that live talk wouldn't hear on the Talk page.
 */
class LiveTalkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Live talk", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as AnuApp
        val live = app.graph.liveTalk
        if (intent?.action == ACTION_STOP) {
            live.stop()
            return START_NOT_STICKY
        }
        val status = live.status.value
        if (status == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            val ok = runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(liveWords(status)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }
            if (ok.isFailure) {
                if (BuildConfig.DEBUG) Log.d("Anu", "Live talk: foreground refused, ${ok.exceptionOrNull()?.javaClass?.simpleName}")
                stopSelf()
                return START_NOT_STICKY
            }
            started = true
            live.serviceRunning = true
            if (BuildConfig.DEBUG) Log.d("Anu", "Live talk: microphone service started")
            scope.launch { follow(app, live) }
        }
        // Not sticky: restarted from the background, it couldn't use the microphone. Live talk starts it again.
        return START_NOT_STICKY
    }

    /** Keeps the notification and the pill in step with live talk; the pill shows only while no Anu screen does. */
    private suspend fun follow(app: AnuApp, live: LiveTalk) {
        val manager = getSystemService(NotificationManager::class.java)
        val control = Flavor.floatingControl
        var pillShown = false
        combine(live.status, app.foreground) { status, open -> status to open }.collectLatest { (status, open) ->
            if (status == null) {
                stopSelf()
                return@collectLatest
            }
            // Partial words and streamed answers change often; the latest one is shown, a quarter second later.
            delay(UPDATE_MS)
            val words = liveWords(status)
            manager.notify(NOTIFICATION_ID, notification(words))
            if (!open && control?.available == true) {
                control.show(words, onOpen = ::openAnu, onStop = live::stop)
                if (!pillShown && BuildConfig.DEBUG) Log.d("Anu", "Live talk: pill shown over another app")
                pillShown = true
            } else if (pillShown) {
                control?.hide()
                pillShown = false
                if (BuildConfig.DEBUG) Log.d("Anu", "Live talk: pill hidden")
            }
            if (BuildConfig.DEBUG) Log.d("Anu", "Live talk: ${status.phase.name.lowercase()}, ${if (open) "in Anu" else "behind another app"}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        Flavor.floatingControl?.hide()
        (application as AnuApp).graph.liveTalk.serviceRunning = false
        if (BuildConfig.DEBUG) Log.d("Anu", "Live talk: microphone service stopped")
        super.onDestroy()
    }

    private fun openAnu() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun notification(words: LiveWords): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveTalkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(words.heading)
            .setContentText(words.line)
            .setSubText("Live talk · heard on this phone")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "End live talk", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val ACTION_STOP = "io.github.sandeepvijayarao09.anu.STOP_LIVE_TALK"
        const val CHANNEL = "live_talk"
        const val NOTIFICATION_ID = 7_101
        const val UPDATE_MS = 250L
    }
}
