package io.github.sandeepvijayarao09.anu.feature.camera

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.model.Box
import io.github.sandeepvijayarao09.anu.core.model.Frame
import io.github.sandeepvijayarao09.anu.core.model.ObjectDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A thing on screen: what YOLOX calls it, how sure it is, where, and how it matches the question, if it does. */
data class Shown(val id: Long, val name: String, val score: Float, val box: Box, val matched: MatchedBy?)

/** The frame "Ask Anu about this" hands to Talk, and what the detector found in it ("cup 91%, donut 90%"). */
class Frozen(val jpeg: ByteArray, val found: String)

/** The things in view as Gemma reads them, most certain first: "cup 91%, donut 90%", at most [limit], or "". */
internal fun foundLine(things: List<Shown>, limit: Int = 12): String =
    things.sortedByDescending { it.score }.take(limit).joinToString(", ") { "${it.name} ${(it.score * 100).toInt()}%" }

enum class CameraStatus { STARTING, RUNNING, PAUSED_HOT, FROZEN, FAILED }

data class LiveCameraState(
    val status: CameraStatus = CameraStatus.STARTING,
    val things: List<Shown> = emptyList(),
    /** The upright frame's size, to place the boxes over the preview. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val model: String = "",
    val backend: String = "",
    /** Frames detected a second, and the time one detection takes, over the last second or so. */
    val fps: Float = 0f,
    val detectMs: Float = 0f,
    val question: String = "",
    /** Whether EmbeddingGemma 2 is installed, so questions are matched by meaning and pictures. */
    val byMeaning: Boolean = false,
    /** The labels the question matches, by name, once known. */
    val matching: List<String> = emptyList(),
    /** The question is being embedded, or the labels' vectors made for the first time. */
    val thinking: Boolean = false,
)

/**
 * Live camera (ADR-111). Each analysis frame goes to the detector on the camera's own thread, things are followed
 * across frames, and the question is matched by meaning, by picture and by words on a thread of the search's own.
 * - Frames live in memory only: the latest one stays only for the next crop or "Ask Anu about this", and none is
 *   saved. Crops are made only while there's a question, and only EmbeddingGemma 2 on this phone sees them.
 * - Heat pauses detection (P8); [frozen] holds the frame "Ask Anu about this" will hand to Talk.
 */
class LiveCameraViewModel(
    private val openDetector: () -> ObjectDetector,
    private val finder: Finder,
    private val labels: List<String>,
    /** Debug builds log the speed every few seconds, numbers only. */
    private val log: ((String) -> Unit)? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val search: CoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "anu-live-search") }.asCoroutineDispatcher(),
) : ViewModel() {
    private val mutable = MutableStateFlow(LiveCameraState(byMeaning = finder.knowsMeaning))
    val state: StateFlow<LiveCameraState> = mutable.asStateFlow()

    /** Held while a frame is detected, so the detector is never closed under it, nor the frame frozen mid-write. */
    private val lock = Any()
    private var closed = false
    private var detector: ObjectDetector? = null
    private val tracker = Tracker()
    private var pixels: IntArray? = null
    @Volatile private var latest: Frame? = null
    @Volatile private var hot = false
    private val looking = AtomicBoolean(false)
    private var asking: Job? = null
    private var lastFrameAt = 0L
    private var lastLogAt = 0L
    private var frames = 0

    init {
        viewModelScope.launch(search) { runCatching { finder.prepare() } }
    }

    /** One CameraX frame, on the camera's thread: RGBA bytes turned upright by [rotation]. */
    fun analyze(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int) = synchronized(lock) {
        if (!ready()) return
        val frame = uprightFrame(rgba, width, height, rowStride, rotation, pixels).also { pixels = it.argb }
        detect(frame)
    }

    /** An upright frame from somewhere other than the camera: debug builds' stills (ADR-111). */
    fun analyze(frame: Frame) = synchronized(lock) {
        if (ready()) detect(frame)
    }

    private fun ready(): Boolean {
        if (closed) return false
        val status = mutable.value.status
        if (status == CameraStatus.FROZEN || status == CameraStatus.FAILED) return false
        if (hot) {
            mutable.update { it.copy(status = CameraStatus.PAUSED_HOT, things = emptyList()) }
            return false
        }
        if (detector == null) {
            try {
                detector = openDetector()
            } catch (e: Exception) {
                mutable.update { it.copy(status = CameraStatus.FAILED) }
                return false
            }
        }
        return true
    }

    private fun detect(frame: Frame) {
        val model = detector ?: return
        val start = clock()
        val found = model.detect(frame)
        val now = clock()
        val tracked = tracker.update(found, now)
        finder.keepOnly(tracked.mapTo(HashSet()) { it.id })
        latest = frame
        lookAtOne(frame, tracked)
        val things = tracked.map { Shown(it.id, model.labels[it.detection.label].name, it.detection.score, it.detection.box, finder.match(it.id, it.detection.label)) }
        val fps = if (lastFrameAt == 0L) 0f else smooth(mutable.value.fps, 1000f / (now - lastFrameAt).coerceAtLeast(1))
        lastFrameAt = now
        mutable.update {
            it.copy(
                status = CameraStatus.RUNNING, things = things, frameWidth = frame.width, frameHeight = frame.height,
                model = model.model, backend = model.backend, fps = fps, detectMs = smooth(it.detectMs, (now - start).toFloat()),
            )
        }
        frames++
        if (log != null && now - lastLogAt >= LOG_EVERY_MS) {
            if (lastLogAt != 0L) log.invoke("Live camera: ${model.model} on ${model.backend}, ${"%.1f".format(fps)} fps, detect ${"%.0f".format(mutable.value.detectMs)} ms, $frames frames, ${things.size} things")
            lastLogAt = now
            frames = 0
        }
    }

    /** Names one thing's picture at a time with EmbeddingGemma 2, while there's a question. */
    private fun lookAtOne(frame: Frame, tracked: List<Tracked>) {
        val next = tracked.firstOrNull { finder.needsPicture(it.id) } ?: return
        if (!looking.compareAndSet(false, true)) return
        val jpeg = crop(frame, next.detection.box).toJpeg(maxSide = CROP_SIDE)
        viewModelScope.launch(search) {
            try {
                finder.look(next.id, jpeg)
            } catch (e: Exception) {
                // A picture that can't be read leaves the thing matched by its label.
            } finally {
                looking.set(false)
            }
        }
    }

    /** A new question, matched once the user pauses typing. */
    fun ask(question: String) {
        mutable.update { it.copy(question = question) }
        asking?.cancel()
        asking = viewModelScope.launch(search) {
            delay(ASK_AFTER_MS)
            mutable.update { it.copy(thinking = question.isNotBlank() && finder.knowsMeaning) }
            try {
                finder.ask(question)
            } catch (e: Exception) {
                // EmbeddingGemma 2 failed to load: the words still match.
            }
            mutable.update { s -> s.copy(thinking = false, matching = if (finder.asking) finder.matching.map(labels::get).sorted() else emptyList()) }
        }
    }

    fun setHot(isHot: Boolean) {
        hot = isHot
        if (!isHot) mutable.update { if (it.status == CameraStatus.PAUSED_HOT) it.copy(status = CameraStatus.STARTING) else it }
    }

    /**
     * Holds the frame on screen for "Ask Anu about this", and returns it as a JPEG for Talk with what the detector found
     * in it, for Gemma to read beside the picture (ADR-111).
     */
    fun freeze(): Frozen? = synchronized(lock) {
        val frame = latest ?: return null
        val state = mutable.updateAndGet { it.copy(status = CameraStatus.FROZEN) }
        Frozen(frame.toJpeg(maxSide = PHOTO_SIDE), foundLine(state.things))
    }

    fun unfreeze() {
        mutable.update { if (it.status == CameraStatus.FROZEN) it.copy(status = CameraStatus.STARTING) else it }
    }

    override fun onCleared() = synchronized(lock) {
        closed = true
        latest = null
        detector?.close()
        detector = null
    }

    private fun smooth(old: Float, new: Float) = if (old == 0f) new else old * 0.8f + new * 0.2f

    private companion object {
        const val ASK_AFTER_MS = 300L
        const val LOG_EVERY_MS = 5_000L
        const val CROP_SIDE = 320
        /** Talk keeps photos at 1,024 pixels on their longer side (ADR-091). */
        const val PHOTO_SIDE = 1024
    }
}
