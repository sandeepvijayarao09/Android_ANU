package io.github.sandeepvijayarao09.anu.core.model

/**
 * One camera frame for live object detection (ADR-111), upright, as packed ARGB pixels in rows. It lives only in
 * memory for one detection: frames are never saved, and nothing but the detector sees them.
 */
class Frame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "A frame needs width × height pixels" }
    }
}

/** Where a detection sits, as fractions of the frame's width and height (0 at the top left, 1 at the bottom right). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    /** Intersection over union, how much two boxes cover the same place. */
    fun overlap(other: Box): Float {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val shared = w * h
        return shared / (area + other.area - shared)
    }
}

/** A thing the detector found: its label's index in [ObjectDetector.labels], how sure it is, and where. */
data class Detection(val label: Int, val score: Float, val box: Box)

/**
 * A label the detector knows, with a short [meaning] in words, so search by meaning can match what the user asks
 * for ("anything to drink") to what the camera sees (ADR-111).
 */
data class DetectorLabel(val name: String, val meaning: String)

/**
 * Finds everyday things in camera frames, on the phone (ADR-111). Not thread-safe: one frame at a time.
 * [close] frees the runtime's memory.
 */
interface ObjectDetector : AutoCloseable {
    /** The model's name, such as "YOLOX-Nano". */
    val model: String

    /** Where it runs, such as "GPU" or "CPU". */
    val backend: String

    val labels: List<DetectorLabel>

    /** The things in [frame], most certain first. */
    fun detect(frame: Frame): List<Detection>
}
