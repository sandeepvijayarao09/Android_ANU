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
 * When memory learns (ADR-037). Never mid-chat: extraction uses the same on-device model, and running
 * it between turns would throw away the chat's KV cache (ADR-033).
 * - Soon after the user leaves the app, if the battery isn't low. The few messages of a session take
 *   one short model call each.
 * - Twice a day while charging, to catch up on anything left (P8).
 * - "Learn now" in the Memory tab runs in the app itself.
 */
object MemoryLearning {
    private const val SOON = "memory-learn"
    private const val CATCH_UP = "memory-catch-up"

    fun soon(context: Context) {
        val request = OneTimeWorkRequestBuilder<MemoryWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleCatchUp(context: Context) {
        val request = PeriodicWorkRequestBuilder<MemoryWorker>(Duration.ofHours(12))
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(CATCH_UP, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Learns from pending messages while the app is out of sight, then frees the model (P8). */
class MemoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AnuApp
        // Stops between messages if the user comes back, so the chat never waits for more than one.
        app.graph.memory.processPending { !app.foreground.value }
        if (!app.foreground.value) app.graph.onDevice.unloadAll()
        return Result.success()
    }
}
