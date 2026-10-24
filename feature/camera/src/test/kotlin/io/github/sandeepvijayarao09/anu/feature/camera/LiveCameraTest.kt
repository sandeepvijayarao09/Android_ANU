package io.github.sandeepvijayarao09.anu.feature.camera

import io.github.sandeepvijayarao09.anu.core.model.Box
import io.github.sandeepvijayarao09.anu.core.model.Detection
import io.github.sandeepvijayarao09.anu.core.model.DetectorLabel
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import io.github.sandeepvijayarao09.anu.core.model.Frame
import io.github.sandeepvijayarao09.anu.core.model.VisualEmbedder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.sqrt

class LiveCameraTest {
    private val labels = listOf(
        DetectorLabel("bottle", "a bottle of water, soda, juice or wine to drink"),
        DetectorLabel("cup", "a cup or mug of coffee or tea to drink"),
        DetectorLabel("chair", "a chair to sit on"),
        DetectorLabel("laptop", "a laptop computer"),
        DetectorLabel("keyboard", "a computer keyboard for typing"),
    )
    private val search = LiveSearch(labels)

    @Test
    fun theTrackerKeepsAnIdWhileAThingStaysInPlace() {
        val tracker = Tracker()
        val cup = Detection(1, 0.9f, Box(0.1f, 0.1f, 0.3f, 0.3f))
        val first = tracker.update(listOf(cup), now = 0).single().id
        assertEquals(first, tracker.update(listOf(cup.copy(box = Box(0.12f, 0.1f, 0.32f, 0.3f))), now = 33).single().id)
        // Missed for a frame, then back: the same thing.
        tracker.update(emptyList(), now = 66)
        assertEquals(first, tracker.update(listOf(cup), now = 99).single().id)
        // A chair in the same place is a different thing, and so is the cup after a long absence.
        assertNotEquals(first, tracker.update(listOf(cup.copy(label = 2)), now = 132).single().id)
        assertNotEquals(first, tracker.update(listOf(cup), now = 5_000).single().id)
    }

    @Test
    fun aCropTakesTheBoxWithAMarginInsideTheFrame() {
        val frame = Frame(100, 50, IntArray(5000) { it })
        val part = crop(frame, Box(0.1f, 0.2f, 0.5f, 0.8f), margin = 0.1f, minSide = 1)
        // 40 × 30 pixels at (10, 10), widened by 4 and 3 each side.
        assertEquals(48, part.width)
        assertEquals(36, part.height)
        assertEquals(frame.argb[7 * 100 + 6], part.argb[0])
        // A box at the edge stays inside, and a tiny one is grown.
        val edge = crop(frame, Box(0.9f, 0.9f, 1f, 1f), minSide = 32)
        assertTrue(edge.width >= 10 && edge.height >= 5)
        assertEquals(32, crop(frame, Box(0.5f, 0.5f, 0.51f, 0.52f), minSide = 32).width)
    }

    @Test
    fun framesAreTurnedUprightAsCameraXReportsThem() {
        // A 3 × 2 image whose red channel numbers its pixels in rows, with two spare bytes at the end of each row.
        val stride = 3 * 4 + 2
        val bytes = ByteBuffer.allocate(stride * 2)
        for (y in 0..1) for (x in 0..2) bytes.put(y * stride + x * 4, (y * 3 + x).toByte())
        fun reds(rotation: Int) = uprightFrame(bytes, 3, 2, stride, rotation).let { f -> f.width to f.argb.map { it shr 16 and 0xFF } }
        assertEquals(3 to listOf(0, 1, 2, 3, 4, 5), reds(0))
        assertEquals(2 to listOf(3, 0, 4, 1, 5, 2), reds(90))
        assertEquals(3 to listOf(5, 4, 3, 2, 1, 0), reds(180))
        assertEquals(2 to listOf(2, 5, 1, 4, 0, 3), reds(270))
        assertEquals(0xFF, uprightFrame(bytes, 3, 2, stride, 0).argb[0] ushr 24)
    }

    @Test
    fun wordsMatchLabelsAndTheirMeanings() {
        assertEquals(setOf(0, 1), search.wordMatches("anything to drink?"))
        assertEquals(setOf(1), search.wordMatches("my coffee mugs"))
        assertEquals(setOf(3, 4), search.wordMatches("Show me a computer"))
        assertEquals(emptySet<Int>(), search.wordMatches("show me something"))
    }

    @Test
    fun meaningPicksTheLabelsWellClearOfTheRest() {
        // As EmbeddingGemma 2 scored "anything to drink" (S14): high for all, three well clear.
        val scores = floatArrayOf(0.862f, 0.837f, 0.73f, 0.72f, 0.74f) + FloatArray(75) { 0.70f + it * 0.0005f }
        assertEquals(setOf(0, 1), search.labelMatches(scores))
        // "My keys": keyboard clears the rest but is below the floor, so nothing matches.
        val keys = FloatArray(80) { 0.68f }.also { it[4] = 0.799f }
        assertEquals(emptySet<Int>(), search.labelMatches(keys))
        assertEquals(3, search.pictureLabel(floatArrayOf(0.1f, 0.2f, 0.3f, 0.6f, 0.5f)))
    }

    /** Words, the question and pictures, each as a direction: drink-ish, sit-ish, or computer-ish. */
    private class FakeEmbedder : VisualEmbedder {
        override val model = "fake"
        var labelRuns = 0
        override suspend fun embed(texts: List<String>, kind: EmbeddingKind): List<FloatArray> {
            if (kind == EmbeddingKind.DOCUMENT) labelRuns++
            return texts.map { t ->
                when {
                    "drink" in t -> vector(1f, 0f, 0f)
                    "sit" in t -> vector(0f, 1f, 0f)
                    else -> vector(0f, 0f, 1f)
                }.let { v -> if (kind == EmbeddingKind.QUERY) v else vector(v[0] + 0.1f, v[1] + 0.1f, v[2] + 0.1f) }
            }
        }

        override suspend fun embedImages(jpegs: List<ByteArray>) = jpegs.map { if (it[0] == 1.toByte()) vector(1f, 0f, 0f) else vector(0f, 1f, 0f) }

        fun vector(a: Float, b: Float, c: Float): FloatArray = floatArrayOf(a, b, c).let { v -> val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(3) { v[it] / n } }
    }

    @Test
    fun theFinderMatchesByMeaningThenByPicture() = runTest {
        val embedder = FakeEmbedder()
        val kept = HashMap<String, List<FloatArray>>()
        val store = object : LabelVectors {
            override fun load(model: String, texts: List<String>) = kept[model]
            override fun save(model: String, texts: List<String>, vectors: List<FloatArray>) { kept[model] = vectors }
        }
        // The fake's vectors are far apart, unlike EmbeddingGemma 2's, so the thresholds are the fake's own.
        val loose = LiveSearch(labels, floor = 0f, aboveMedian = 0f, margin = 0.05f)
        val finder = Finder(loose, { embedder }, store)
        assertNull("nothing asked yet", finder.match(1, 0))
        finder.ask("somewhere to sit")
        assertEquals(1, embedder.labelRuns)
        assertEquals(setOf(2), finder.matching)
        assertEquals(MatchedBy.LABEL, finder.match(1, 2))
        assertNull(finder.match(1, 0))
        // A picture EmbeddingGemma 2 names as a drink doesn't match; one it names as a chair does, whatever YOLOX said.
        assertTrue(finder.needsPicture(7))
        assertEquals(0, finder.look(7, byteArrayOf(1)))
        assertFalse(finder.needsPicture(7))
        assertNull(finder.match(7, 0))
        assertEquals(2, finder.look(8, byteArrayOf(2)))
        assertEquals(MatchedBy.PICTURE, finder.match(8, 0))
        // Things that leave are forgotten; leaving and coming back reuses the labels' vectors.
        finder.keepOnly(setOf(8))
        assertTrue(finder.needsPicture(7))
        Finder(loose, { embedder }, store).ask("anything to drink")
        assertEquals(1, embedder.labelRuns)
    }

    @Test
    fun withoutEmbeddingGemmaTwoItMatchesWords() = runTest {
        val finder = Finder(search, { null })
        finder.ask("anything to drink")
        assertEquals(MatchedBy.WORDS, finder.match(1, 0))
        assertNull(finder.match(2, 2))
        assertFalse(finder.needsPicture(1))
        finder.ask("")
        assertNull(finder.match(1, 0))
    }

    @Test
    fun thePanelSaysWhatItFound() {
        val cup = Shown(1, "cup", 0.9f, Box(0f, 0f, 1f, 1f), MatchedBy.LABEL)
        val chair = Shown(2, "chair", 0.8f, Box(0f, 0f, 1f, 1f), null)
        assertEquals("Point at things around you. Anu finds them on this phone.", line(LiveCameraState()))
        assertEquals("In view: cup, chair", line(LiveCameraState(things = listOf(cup.copy(matched = null), chair))))
        val asked = LiveCameraState(question = "anything to drink", byMeaning = true, matching = listOf("bottle", "cup", "wine glass"))
        assertEquals("1 in view: cup", line(asked.copy(things = listOf(cup, chair))))
        assertEquals("Looking for bottle, cup or wine glass", line(asked.copy(things = listOf(chair))))
        assertEquals("Anu can spot 80 everyday things, and none of them fits that.", line(asked.copy(matching = emptyList())))
        assertEquals("Matching by meaning…", line(asked.copy(thinking = true)))
    }
}
