package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelInfo
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.model.OnDeviceModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.roundToInt

/**
 * Finds, downloads and configures the on-device models (ARCHITECTURE §5).
 * - Files live in `noBackupFilesDir/models` and are never backed up (§14).
 * - The weight cache lives in `noBackupFilesDir/litertlm-cache` (ADR-020).
 * - Downloads run as unique WorkManager jobs on unmetered networks, and resume after interruptions.
 */
class ModelManager(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val preferred: ModelVariant = defaultVariant(),
) : OnDeviceModels {
    private val modelsDir = File(context.noBackupFilesDir, "models")
    private val weightCacheDir = File(context.noBackupFilesDir, "litertlm-cache")

    fun artifactFor(brain: BrainId): ModelArtifact = ModelCatalog.choose(brain, Build.SOC_MODEL, preferred)

    /** The verified file, if present. Files are renamed into place only after their SHA-256 check. */
    fun installedFile(brain: BrainId): File? {
        val artifact = artifactFor(brain)
        return File(modelsDir, artifact.fileName).takeIf { it.length() == artifact.sizeBytes }
    }

    override fun info(id: BrainId): ModelInfo =
        artifactFor(id).let { ModelInfo(id, it.fileName, it.sizeBytes, it.variant.name, it.cacheBytes, ModelCatalog.minMemoryGb(id)) }

    override val deviceMemoryGb: Int by lazy {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val advertised = if (Build.VERSION.SDK_INT >= 34) memory.advertisedMem else 0
        memoryGb(memory.totalMem, advertised)
    }

    override fun isInstalled(id: BrainId): Boolean = installedFile(id) != null

    override fun spaceUsed(id: BrainId): Long = ModelFiles.spaceUsed(modelsDir, weightCacheDir, artifactFor(id).fileName)

    override fun state(id: BrainId): Flow<ModelState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(artifactFor(id))).map { infos ->
            val info = infos.lastOrNull()
            when {
                installedFile(id) != null -> ModelState.Ready
                info == null -> ModelState.NotInstalled
                info.state == WorkInfo.State.FAILED -> ModelState.Failed(info.outputData.getString(KEY_ERROR) ?: "download failed")
                info.state.isFinished -> ModelState.NotInstalled
                else -> ModelState.Downloading(
                    info.progress.getLong(KEY_DONE, 0),
                    info.progress.getLong(KEY_TOTAL, artifactFor(id).sizeBytes),
                    info.progress.getLong(KEY_RATE, 0),
                )
            }
        }

    override fun download(id: BrainId) {
        val artifact = artifactFor(id)
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_FILE to artifact.fileName))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.UNMETERED, requiresStorageNotLow = true))
            .build()
        workManager.enqueueUniqueWork(workName(artifact), ExistingWorkPolicy.KEEP, request)
    }

    override fun delete(id: BrainId) {
        val artifact = artifactFor(id)
        workManager.cancelUniqueWork(workName(artifact))
        ModelFiles.delete(modelsDir, weightCacheDir, artifact.fileName)
    }

    /** Engine settings for an installed model, or null if it isn't installed yet. */
    fun engineConfig(brain: BrainId, maxNumTokens: Int): EngineConfig? {
        val file = installedFile(brain) ?: return null
        return EngineConfig(
            modelPath = file.absolutePath,
            backend = backendFor(artifactFor(brain).variant),
            maxNumTokens = maxNumTokens,
            cacheDir = weightCacheDir.apply { mkdirs() }.absolutePath,
        )
    }

    /** Runtime options for [brain]: the runtime's counters when [benchmark] is set, for the debug speed log. */
    fun engineOptions(brain: BrainId, benchmark: Boolean): EngineOptions = EngineOptions(benchmark = benchmark)

    private fun backendFor(variant: ModelVariant): Backend = when (variant) {
        ModelVariant.CPU -> Backend.CPU()
        ModelVariant.GPU -> Backend.GPU()
        ModelVariant.NPU_TENSOR_G5, ModelVariant.NPU_TENSOR_G6 -> Backend.GOOGLE_TENSOR()
        ModelVariant.NPU_SNAPDRAGON_SM8750 -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
    }

    internal companion object {
        const val KEY_FILE = "file"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_RATE = "rate"
        const val KEY_ERROR = "error"

        fun workName(artifact: ModelArtifact) = "model-download-${artifact.fileName}"

        /**
         * The CPU build, unless this chip's GPU build has passed S1 (ADR-059). Emulators have no OpenCL for
         * LiteRT's GPU backend (ADR-025).
         */
        fun defaultVariant(): ModelVariant = ModelCatalog.preferredVariant(Build.SOC_MODEL, isEmulator())

        private fun isEmulator(): Boolean =
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.PRODUCT.startsWith("sdk_gphone")

        fun modelsDir(context: Context) = File(context.noBackupFilesDir, "models")

        /**
         * The phone's memory in GB as it's sold (ADR-066). Android 14 reports that as [advertised], a little more than
         * the [total] the system can use: 16 GiB on the Pixel 10 Pro, which has 15.2 GiB to use. Devices give it in
         * binary or decimal units, and it's trusted only when it's close to the total: the 10 GB emulator claims
         * 16,000,000,000 bytes. Otherwise the total counts, in GiB.
         */
        fun memoryGb(total: Long, advertised: Long): Int = when {
            advertised <= 0 || advertised > total * 1.25 -> (total / GIB).roundToInt()
            advertised % GIB.toLong() == 0L -> (advertised / GIB).roundToInt()
            else -> (advertised / 1e9).roundToInt()
        }

        private const val GIB = 1024.0 * 1024 * 1024
    }
}

/** A model's files on disk: the model, a partial download, and the weight caches the runtime wrote for it (ADR-061). */
internal object ModelFiles {
    /** The caches written for [fileName], such as `gemma-4-E4B-it.litertlm_1790527891_3659530240.xnnpack_cache`. */
    fun caches(cacheDir: File, fileName: String): List<File> =
        cacheDir.listFiles { file -> file.name.startsWith("${fileName}_") }?.toList().orEmpty()

    fun spaceUsed(modelsDir: File, cacheDir: File, fileName: String): Long =
        File(modelsDir, fileName).length() + File(modelsDir, "$fileName.part").length() + caches(cacheDir, fileName).sumOf { it.length() }

    /** Deletes the model with its cache, so deleting frees all the space it took. */
    fun delete(modelsDir: File, cacheDir: File, fileName: String) {
        File(modelsDir, fileName).delete()
        File(modelsDir, "$fileName.part").delete()
        caches(cacheDir, fileName).forEach(File::delete)
    }
}

/** Downloads one model file in the foreground, because the files are 2–4 GB (§5). */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val artifact = ModelCatalog.all.firstOrNull { it.fileName == inputData.getString(ModelManager.KEY_FILE) }
            ?: return Result.failure(workDataOf(ModelManager.KEY_ERROR to "unknown model file"))
        setForeground(foregroundInfo(artifact, 0))
        var lastPercent = -1
        val started = SystemClock.elapsedRealtime()
        var from = -1L
        return try {
            ResumableDownload(OkHttpClient()).run(artifact, File(ModelManager.modelsDir(applicationContext), artifact.fileName)) { done, total ->
                if (from < 0) from = done
                val percent = (done * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgressAsync(workDataOf(ModelManager.KEY_DONE to done, ModelManager.KEY_TOTAL to total, ModelManager.KEY_RATE to rate(done - from, started)))
                    setForegroundAsync(foregroundInfo(artifact, percent))
                }
            }
            Result.success()
        } catch (e: ModelDownloadException) {
            if (e.retryable) Result.retry() else Result.failure(workDataOf(ModelManager.KEY_ERROR to e.message))
        } catch (e: java.io.IOException) {
            Result.retry()
        }
    }

    /** The average since this run started, once there are two seconds to go on, so the first figure isn't a guess. */
    private fun rate(bytes: Long, started: Long): Long {
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        return if (seconds < 2) 0 else (bytes / seconds).toLong()
    }

    private fun foregroundInfo(artifact: ModelArtifact, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${if (artifact.brain == BrainId.BASE) "Base" else "Pro"} model")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID + artifact.brain.ordinal, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val CHANNEL = "model-downloads"
        const val NOTIFICATION_ID = 4100
    }
}
