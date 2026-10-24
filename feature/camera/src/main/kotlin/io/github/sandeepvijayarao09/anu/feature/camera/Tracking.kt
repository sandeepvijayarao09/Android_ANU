package io.github.sandeepvijayarao09.anu.feature.camera

import io.github.sandeepvijayarao09.anu.core.model.Box
import io.github.sandeepvijayarao09.anu.core.model.Detection
import io.github.sandeepvijayarao09.anu.core.model.Frame
import kotlin.math.roundToInt

/** A thing followed from frame to frame (ADR-111): the same [id] while it stays in about the same place. */
data class Tracked(val id: Long, val detection: Detection, val seenAt: Long)

/**
 * Gives each detection the id of the one it continues from the last frame: the same label, overlapping by at least
 * [overlap]. So a crop's picture vector, which takes far longer than a frame, is kept for the thing it shows. A thing
 * missing for [forgetMs] is forgotten.
 */
class Tracker(private val overlap: Float = 0.3f, private val forgetMs: Long = 1_000) {
    private var tracks = emptyList<Tracked>()
    private var next = 1L

    fun update(found: List<Detection>, now: Long): List<Tracked> {
        val open = tracks.filter { now - it.seenAt <= forgetMs }.toMutableList()
        val current = found.map { d ->
            val best = open.filter { it.detection.label == d.label }.maxByOrNull { it.detection.box.overlap(d.box) }
            if (best != null && best.detection.box.overlap(d.box) >= overlap) {
                open.remove(best)
                Tracked(best.id, d, now)
            } else {
                Tracked(next++, d, now)
            }
        }
        // Things just missed this frame stay a moment, so a box that flickers keeps its id.
        tracks = current + open
        return current
    }
}

/**
 * The part of [frame] inside [box], widened by [margin] of its size on each side so the thing's edges are in the
 * picture, and at least [minSide] pixels a side where the frame allows.
 */
fun crop(frame: Frame, box: Box, margin: Float = 0.1f, minSide: Int = 32): Frame {
    val padX = box.width * margin
    val padY = box.height * margin
    var left = ((box.left - padX) * frame.width).roundToInt().coerceIn(0, frame.width - 1)
    var top = ((box.top - padY) * frame.height).roundToInt().coerceIn(0, frame.height - 1)
    var right = ((box.right + padX) * frame.width).roundToInt().coerceIn(left + 1, frame.width)
    var bottom = ((box.bottom + padY) * frame.height).roundToInt().coerceIn(top + 1, frame.height)
    if (right - left < minSide) {
        val grow = minSide - (right - left)
        left = (left - grow / 2).coerceAtLeast(0)
        right = (left + minSide).coerceAtMost(frame.width)
    }
    if (bottom - top < minSide) {
        val grow = minSide - (bottom - top)
        top = (top - grow / 2).coerceAtLeast(0)
        bottom = (top + minSide).coerceAtMost(frame.height)
    }
    val w = right - left
    val h = bottom - top
    val pixels = IntArray(w * h)
    for (y in 0 until h) System.arraycopy(frame.argb, (top + y) * frame.width + left, pixels, y * w, w)
    return Frame(w, h, pixels)
}
