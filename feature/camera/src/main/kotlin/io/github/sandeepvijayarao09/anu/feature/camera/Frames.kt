package io.github.sandeepvijayarao09.anu.feature.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import io.github.sandeepvijayarao09.anu.core.model.Frame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * A camera image's RGBA bytes ([rowStride] bytes a row, 4 a pixel) as an upright [Frame], turned by [rotation]
 * degrees clockwise as CameraX reports it. [into] is reused when it's the right size, so the camera doesn't make a
 * new array for every frame.
 */
fun uprightFrame(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, into: IntArray? = null): Frame {
    val turned = rotation == 90 || rotation == 270
    val outW = if (turned) height else width
    val outH = if (turned) width else height
    val out = into?.takeIf { it.size == outW * outH } ?: IntArray(outW * outH)
    // Where source pixel (x, y) lands: start + x × stepX + y × stepY in the upright array.
    val (start, stepX, stepY) = when (rotation) {
        90 -> Triple(height - 1, outW, -1)
        180 -> Triple(outW * outH - 1, -1, -outW)
        270 -> Triple((outH - 1) * outW, -outW, 1)
        else -> Triple(0, 1, outW)
    }
    for (y in 0 until height) {
        var at = y * rowStride
        var to = start + y * stepY
        for (x in 0 until width) {
            val r = rgba.get(at).toInt() and 0xFF
            val g = rgba.get(at + 1).toInt() and 0xFF
            val b = rgba.get(at + 2).toInt() and 0xFF
            out[to] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            at += 4
            to += stepX
        }
    }
    return Frame(outW, outH, out)
}

/** [frame] as a JPEG at most [maxSide] pixels on its longer side, for a picture vector or a question to Anu. */
fun Frame.toJpeg(maxSide: Int = 1024, quality: Int = 85): ByteArray {
    var bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    val longer = max(width, height)
    if (longer > maxSide) {
        val scale = maxSide.toFloat() / longer
        bitmap = Bitmap.createScaledBitmap(bitmap, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
    }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}

/** A [Bitmap] turned upright by [rotation] degrees, as a captured photo comes from the camera. */
fun Bitmap.upright(rotation: Int): Bitmap =
    if (rotation == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
