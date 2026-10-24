package io.github.sandeepvijayarao09.anu.brain.vision

import io.github.sandeepvijayarao09.anu.core.model.Box
import io.github.sandeepvijayarao09.anu.core.model.Detection
import io.github.sandeepvijayarao09.anu.core.model.Frame
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOX's input and output in plain Kotlin (ADR-111), kept out of the graph so the whole graph runs on the GPU:
 * - [letterbox] fits a frame into the model's square, scaled evenly and padded at the right and bottom with grey
 *   114, as BGR values 0–255 in rows (NHWC), as YOLOX was trained.
 * - [decode] turns the raw heads, one row of 4 box values, objectness and 80 class scores per grid cell at strides
 *   8, 16 and 32, into boxes in the frame, then keeps the best of each overlapping group of a class (NMS).
 */
class Yolox(val size: Int = SIZE, val classes: Int = CLASSES) {
    /** The grid cells in the model's output order: x, y and stride for each. */
    private val cells: IntArray = buildList {
        for (stride in STRIDES) {
            val n = this@Yolox.size / stride
            for (y in 0 until n) for (x in 0 until n) { add(x); add(y); add(stride) }
        }
    }.toIntArray()

    val anchors: Int get() = cells.size / 3

    /** How much the frame was scaled to fit; [decode] divides by it to map boxes back. */
    fun scale(frame: Frame): Float = min(size.toFloat() / frame.width, size.toFloat() / frame.height)

    /** The model's input for [frame], written into [into] (size × size × 3) so a running camera reuses one array. */
    fun letterbox(frame: Frame, into: FloatArray = FloatArray(size * size * 3)): FloatArray {
        require(into.size == size * size * 3)
        val r = scale(frame)
        val w = (frame.width * r).roundToInt().coerceIn(1, size)
        val h = (frame.height * r).roundToInt().coerceIn(1, size)
        into.fill(PAD)
        // Bilinear: nearest pixels lost small people at the edge of a street photo (S14).
        val x0 = IntArray(w)
        val fx = FloatArray(w)
        for (x in 0 until w) {
            val sx = ((x + 0.5f) / r - 0.5f).coerceIn(0f, frame.width - 1f)
            x0[x] = sx.toInt()
            fx[x] = sx - x0[x]
        }
        val pixels = frame.argb
        for (y in 0 until h) {
            val sy = ((y + 0.5f) / r - 0.5f).coerceIn(0f, frame.height - 1f)
            val y0 = sy.toInt()
            val fy = sy - y0
            val top = y0 * frame.width
            val bottom = min(y0 + 1, frame.height - 1) * frame.width
            var out = y * size * 3
            for (x in 0 until w) {
                val left = x0[x]
                val right = min(left + 1, frame.width - 1)
                val a = pixels[top + left]
                val b = pixels[top + right]
                val c = pixels[bottom + left]
                val d = pixels[bottom + right]
                val t = fx[x]
                // Blue, green, red: YOLOX was trained on OpenCV's BGR.
                for (k in 0..2) {
                    val shift = k * 8
                    val upper = (a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t
                    val lower = (c shr shift and 0xFF) + ((d shr shift and 0xFF) - (c shr shift and 0xFF)) * t
                    into[out + k] = upper + (lower - upper) * fy
                }
                out += 3
            }
        }
        return into
    }

    /**
     * The detections in [raw] (anchors × (5 + classes)) for a frame of [width] × [height] that was scaled by
     * [scale], with a score (objectness × class) of at least [threshold], at most [limit], most certain first.
     */
    fun decode(raw: FloatArray, scale: Float, width: Int, height: Int, threshold: Float = THRESHOLD, limit: Int = LIMIT): List<Detection> {
        val row = 5 + classes
        require(raw.size == anchors * row) { "Expected ${anchors * row} values, got ${raw.size}" }
        val found = ArrayList<Detection>()
        for (i in 0 until anchors) {
            val at = i * row
            val objectness = raw[at + 4]
            if (objectness < threshold) continue
            var best = 0
            var bestScore = raw[at + 5]
            for (c in 1 until classes) {
                val s = raw[at + 5 + c]
                if (s > bestScore) { bestScore = s; best = c }
            }
            val score = objectness * bestScore
            if (score < threshold) continue
            val stride = cells[i * 3 + 2].toFloat()
            val cx = (raw[at] + cells[i * 3]) * stride
            val cy = (raw[at + 1] + cells[i * 3 + 1]) * stride
            val w = exp(raw[at + 2]) * stride
            val h = exp(raw[at + 3]) * stride
            val box = Box(
                left = ((cx - w / 2) / scale / width).coerceIn(0f, 1f),
                top = ((cy - h / 2) / scale / height).coerceIn(0f, 1f),
                right = ((cx + w / 2) / scale / width).coerceIn(0f, 1f),
                bottom = ((cy + h / 2) / scale / height).coerceIn(0f, 1f),
            )
            if (box.area > 0f) found += Detection(best, score, box)
        }
        return suppress(found, limit)
    }

    companion object {
        const val SIZE = 416
        const val CLASSES = 80
        const val THRESHOLD = 0.35f
        const val OVERLAP = 0.45f
        const val LIMIT = 30
        private const val PAD = 114f
        private val STRIDES = intArrayOf(8, 16, 32)

        /** Keeps the most certain box of each group of one class that overlap by more than [overlap]. */
        fun suppress(found: List<Detection>, limit: Int = LIMIT, overlap: Float = OVERLAP): List<Detection> {
            val kept = ArrayList<Detection>()
            for (d in found.sortedByDescending { it.score }) {
                if (kept.size == limit) break
                if (kept.none { it.label == d.label && it.box.overlap(d.box) > overlap }) kept += d
            }
            return kept
        }
    }
}
