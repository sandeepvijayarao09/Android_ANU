package io.github.sandeepvijayarao09.anu

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration

/**
 * When memory learns (ADR-037, ADR-064). Never mid-chat: extraction uses the same on-device model, and running
 * it between turns would throw away the chat's KV cache (ADR-033). Never while the phone is in use either:
 * learning loads E4B, and on a phone short of memory it slowed the next app the user opened.
 * - Queued when the user leaves the app, it runs once the phone is idle and charging, such as overnight.
 * - Twice a day, on the same conditions, to catch up on anything left (P8).
 * - "Learn now" in the Memory tab runs in the app itself, at once.
 */
object MemoryLearning {
    private const val LEARN = "memory-learn"
    private const val CATCH_UP = "memory-catch-up"

    /** Idle, charging and not low (ADR-064). */
    internal fun constraints(): Constraints =
        Constraints.Builder().setRequiresDeviceIdle(true).setRequiresCharging(true).setRequiresBatteryNotLow(true).build()

    fun whenIdle(context: Context) {
        val request = OneTimeWorkRequestBuilder<MemoryWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(LEARN, ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleCatchUp(context: Context) {
        val request = PeriodicWorkRequestBuilder<MemoryWorker>(Duration.ofHours(12)).setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(CATCH_UP, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Learns from pending messages while the app is out of sight, then frees the model (P8). */
class MemoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AnuApp
        // Stops between messages if the user comes back, so the chat never waits for more than one.
        app.graph.memory.processPending { !app.foreground.value }
        // Facts learned before search by meaning was installed get their vectors here, out of sight (ADR-055).
        if (!app.foreground.value) runCatching { app.graph.memory.index() }
        if (!app.foreground.value) app.graph.onDevice.unloadAll()
        return Result.success()
    }
}
