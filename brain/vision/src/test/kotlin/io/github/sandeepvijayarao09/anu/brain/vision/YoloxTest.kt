package io.github.sandeepvijayarao09.anu.brain.vision

import io.github.sandeepvijayarao09.anu.core.model.Box
import io.github.sandeepvijayarao09.anu.core.model.Detection
import io.github.sandeepvijayarao09.anu.core.model.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ln

class YoloxTest {
    private val yolox = Yolox()
    private val row = 5 + Yolox.CLASSES

    @Test
    fun letterboxScalesEvenlyPadsGreyAndWritesBgr() {
        // A 832 × 416 frame, red on the left half and blue on the right: it fits as 416 × 208 at the top.
        val frame = Frame(832, 416, IntArray(832 * 416) { i -> if (i % 832 < 416) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() })
        assertEquals(0.5f, yolox.scale(frame))
        val input = yolox.letterbox(frame)
        fun pixel(x: Int, y: Int) = (0..2).map { input[(y * Yolox.SIZE + x) * 3 + it] }
        assertEquals(listOf(0f, 0f, 255f), pixel(10, 10)) // red, as B, G, R
        assertEquals(listOf(255f, 0f, 0f), pixel(400, 10)) // blue
        assertEquals(listOf(114f, 114f, 114f), pixel(10, 300)) // padding below the picture
    }

    @Test
    fun decodesACellIntoABoxInTheFrame() {
        val raw = FloatArray(yolox.anchors * row)
        // Cell (10, 5) at stride 8: centre (10.5, 5.5) × 8 = (84, 44), 32 × 16 in the model's square.
        val cell = 5 * 52 + 10
        raw[cell * row] = 0.5f; raw[cell * row + 1] = 0.5f
        raw[cell * row + 2] = ln(4f); raw[cell * row + 3] = ln(2f)
        raw[cell * row + 4] = 0.9f
        raw[cell * row + 5 + 41] = 0.8f // cup
        val found = yolox.decode(raw, scale = 0.5f, width = 832, height = 416)
        assertEquals(1, found.size)
        val d = found.single()
        assertEquals(41, d.label)
        assertEquals(0.72f, d.score, 1e-5f)
        // In the frame: centre (168, 88), 64 × 32 pixels of 832 × 416.
        assertEquals(136f / 832, d.box.left, 1e-4f)
        assertEquals(72f / 416, d.box.top, 1e-4f)
        assertEquals(200f / 832, d.box.right, 1e-4f)
        assertEquals(104f / 416, d.box.bottom, 1e-4f)
    }

    @Test
    fun dropsUnsureCellsAndUsesTheLaterStrides() {
        val raw = FloatArray(yolox.anchors * row)
        raw[4] = 0.3f; raw[5] = 1f // below the threshold
        val last = yolox.anchors - 1 // stride 32, cell (12, 12)
        raw[last * row + 4] = 1f; raw[last * row + 5] = 0.9f
        val found = yolox.decode(raw, scale = 1f, width = 416, height = 416)
        assertEquals(1, found.size)
        // Centre (12 × 32, 12 × 32) = (384, 384), 32 × 32.
        assertEquals((384f - 16) / 416, found.single().box.left, 1e-4f)
        assertEquals((384f + 16) / 416, found.single().box.bottom, 1e-4f)
    }

    @Test
    fun suppressionKeepsTheBestOfOverlappingBoxesOfOneClass() {
        val a = Detection(0, 0.9f, Box(0.1f, 0.1f, 0.5f, 0.5f))
        val b = Detection(0, 0.8f, Box(0.12f, 0.1f, 0.52f, 0.5f)) // the same person again
        val c = Detection(1, 0.7f, Box(0.12f, 0.1f, 0.52f, 0.5f)) // a different thing in the same place
        val d = Detection(0, 0.6f, Box(0.6f, 0.6f, 0.9f, 0.9f)) // another person
        assertEquals(listOf(a, c, d), Yolox.suppress(listOf(d, b, c, a)))
        assertEquals(listOf(a), Yolox.suppress(listOf(d, b, c, a), limit = 1))
    }

    @Test
    fun knowsEightyLabelsEachWithAMeaning() {
        assertEquals(Yolox.CLASSES, CocoLabels.ALL.size)
        assertEquals("cup", CocoLabels.ALL[41].name)
        assertEquals("toothbrush", CocoLabels.ALL[79].name)
        assertTrue(CocoLabels.ALL.all { it.meaning.isNotBlank() })
    }

    @Test
    fun shipsTheModelFileFromLitertCommunity() {
        val file = File("src/main/assets/${YoloxDetector.ASSET}")
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(MODEL_SHA256, sha)
        assertTrue(File("src/main/assets/licenses/YOLOX-LICENSE.txt").readText().contains("Apache License"))
    }

    private companion object {
        /** huggingface.co/litert-community/yolox-nano-litert, yolox_nano.tflite, 2,236,912 bytes. */
        const val MODEL_SHA256 = "3a621c40141760da2435891093616e4d8cfa8748000c72ced4e536d694f970e4"
    }
}
