package io.github.sandeepvijayarao09.anu

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.sandeepvijayarao09.anu.core.voice.SpeechInput
import io.github.sandeepvijayarao09.anu.core.voice.WakeListener
import io.github.sandeepvijayarao09.anu.feature.settings.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Anu as the phone's assistant, and the "Hey Anu" switch (ADR-044, ADR-045). Android decides which app
 * is the assistant, so the settings screen sends the user to Android's page for it.
 */
class AnuVoiceSettings(private val context: Context, private val prefs: SharedPreferences) : VoiceSettings {
    private val on = MutableStateFlow(prefs.getBoolean(KEY_WAKE_WORD, false))
    override val wakeWord: StateFlow<Boolean> = on.asStateFlow()

    override fun isAssistant(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

    // The assistant role can't be requested with a dialog, only chosen in Android's settings.
    override fun assistantSettingsIntents(): List<Intent> =
        listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

    override fun setWakeWord(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WAKE_WORD, enabled) }
        on.value = enabled
        if (enabled) resume() else context.stopService(Intent(context, WakeWordService::class.java))
    }

    /**
     * Starts listening if the switch is on. Android lets a microphone service start only while Anu is on
     * screen, so this runs whenever an Anu screen opens, which also brings it back after a restart.
     */
    fun resume() {
        if (!on.value || !isAssistant()) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
    }

    private companion object {
        const val KEY_WAKE_WORD = "wake_word"
    }
}

/**
 * "Hey Anu" (ADR-045). While the screen is on and no Anu screen is showing, the phone's on-device
 * recognizer listens in short rounds.
 * - What it hears is only checked for the wake phrase, then dropped: it is never kept, logged or sent.
 * - On a match, the assistant opens with whatever followed the phrase, often the request itself.
 * - It runs as a foreground service with a notification, so the user can always see that the microphone
 *   is on and stop it (§12).
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private val screenOn = MutableStateFlow(true)

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn.value = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Hey Anu", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as AnuApp).graph.voice.setWakeWord(false)
            return START_NOT_STICKY
        }
        // Throws when Android doesn't allow a microphone service right now, such as from the background.
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(Status.LISTENING), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) loop = scope.launch { run() }
        // Not sticky: a restart from the background couldn't use the microphone. Opening Anu restarts it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(screen)
        super.onDestroy()
    }

    private suspend fun run() {
        val app = application as AnuApp
        combine(screenOn, app.foreground) { on, open ->
            when {
                !on -> Status.SCREEN_OFF
                open -> Status.ANU_OPEN
                else -> Status.LISTENING
            }
        }.distinctUntilChanged().collectLatest { status ->
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
            // Anu's own screens use the microphone themselves, so listening stops while one is showing.
            if (status == Status.LISTENING) listen(app.graph.speechIn)
        }
    }

    /** Listens until cancelled, or turns itself off when it can't go on. */
    private suspend fun listen(speech: SpeechInput) {
        val stop = WakeListener(speech, SystemClock::elapsedRealtime).run { request ->
            // The assistant opens, and listening pauses because an Anu screen is showing.
            AnuVoiceInteractionService.show(request).also { opened -> if (opened) delay(1_000) }
        }
        turnOff(
            when (stop) {
                WakeListener.Stop.CantOpen -> "Make Anu your assistant in Settings, so “Hey Anu” can open it."
                is WakeListener.Stop.Failed -> stop.reason
            },
        )
    }

    /** Switches "Hey Anu" off and says why, in a notification the user can dismiss. */
    private fun turnOff(reason: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTICE_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("“Hey Anu” is off")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setContentIntent(openAnu())
                .setAutoCancel(true)
                .build(),
        )
        (application as AnuApp).graph.voice.setWakeWord(false)
    }

    private fun notification(status: Status): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(
                when (status) {
                    Status.LISTENING -> "Listening for “Hey Anu”"
                    Status.ANU_OPEN -> "“Hey Anu” is paused while Anu is open"
                    Status.SCREEN_OFF -> "“Hey Anu” is paused while the screen is off"
                },
            )
            .setContentText("Heard on this phone only. Nothing is kept or sent.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAnu())
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openAnu(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private enum class Status { LISTENING, ANU_OPEN, SCREEN_OFF }

    private companion object {
        const val ACTION_STOP = "io.github.sandeepvijayarao09.anu.STOP_WAKE_WORD"
        const val CHANNEL = "wake_word"
        const val NOTIFICATION_ID = 7_001
        const val NOTICE_ID = 7_002
    }
}
