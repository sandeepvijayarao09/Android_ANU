package io.github.sandeepvijayarao09.anu.feature.camera

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandeepvijayarao09.anu.brain.embedding.EmbeddingCatalog
import io.github.sandeepvijayarao09.anu.brain.embedding.Gemma2Embedder
import io.github.sandeepvijayarao09.anu.brain.vision.YoloxDetector
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import io.github.sandeepvijayarao09.anu.core.model.Frame
import io.github.sandeepvijayarao09.anu.core.model.closeness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike S14's search eval (ADR-111): YOLOX finds things in real photos, EmbeddingGemma 2 embeds each crop, the
 * label texts and a few queries, and every closeness is logged, to set the live camera's thresholds. Needs the
 * model in /data/local/tmp/anu/models and the photos from scripts/live-camera-photos.sh; it skips without them.
 * Results: `adb logcat -d -s AnuS14`.
 */
@RunWith(AndroidJUnit4::class)
class LiveSearchEvalTest {
    @Test
    fun logsHowQueriesMeetLabelsAndCrops() = runBlocking {
        val model = File("/data/local/tmp/anu/models", EmbeddingCatalog.VISION.name)
        val photos = File("/data/local/tmp/anu/photos/live").listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("EmbeddingGemma 2 isn't in /data/local/tmp/anu/models", model.length() == EmbeddingCatalog.VISION.bytes)
        assumeTrue("No photos: run scripts/live-camera-photos.sh", photos.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val embedder = Gemma2Embedder(model, File(context.cacheDir, "eg2-cache"))
        val search = LiveSearch(io.github.sandeepvijayarao09.anu.brain.vision.CocoLabels.ALL)
        val queries = listOf("anything to drink", "something to eat", "somewhere to sit", "a computer", "my keys")
        val start = SystemClock.elapsedRealtime()
        val q = embedder.embed(queries, EmbeddingKind.QUERY)
        Log.i(TAG, "eval load_and_queries_ms=${SystemClock.elapsedRealtime() - start}")
        val t0 = SystemClock.elapsedRealtime()
        val labelVectors = embedder.embed(search.labelTexts, EmbeddingKind.DOCUMENT)
        Log.i(TAG, "eval labels_ms=${SystemClock.elapsedRealtime() - t0} for ${labelVectors.size}")
        val matched = queries.indices.map { qi ->
            val scores = FloatArray(labelVectors.size) { closeness(q[qi], labelVectors[it]) }
            val ranked = scores.indices.sortedByDescending { scores[it] }
            Log.i(TAG, "eval label_scores query=\"${queries[qi]}\" " + ranked.take(10).joinToString(" ") { "${search.labels[it].name}=${"%.3f".format(scores[it])}" } +
                " | median=${"%.3f".format(scores.sorted()[scores.size / 2])}")
            search.labelMatches(scores).map { search.labels[it].name }.toSet().also { Log.i(TAG, "eval matches query=\"${queries[qi]}\" $it") }
        }
        var named = 0
        var agreed = 0
        YoloxDetector.open(context.assets).use { detector ->
            for (photo in photos) {
                val bitmap = BitmapFactory.decodeFile(photo.path)
                val frame = Frame(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) })
                val found = detector.detect(frame)
                val whole = embedder.embedImages(listOf(frame.toJpeg(512)))
                Log.i(TAG, "eval photo=${photo.name} found=${found.size} whole " + queries.indices.joinToString(" ") { "q$it=${"%.3f".format(closeness(q[it], whole[0]))}" })
                for (d in found) {
                    val c0 = SystemClock.elapsedRealtime()
                    val v = embedder.embedImages(listOf(crop(frame, d.box).toJpeg(320))).single()
                    val ms = SystemClock.elapsedRealtime() - c0
                    // How each query ranks among the crop's closeness to the 80 label texts: 1.0 beats them all.
                    val toLabels = labelVectors.map { closeness(it, v) }
                    val top = toLabels.indices.sortedByDescending { toLabels[it] }.take(3).joinToString(",") { search.labels[it].name }
                    named++
                    if (search.pictureLabel(toLabels.toFloatArray()) == d.label) agreed++
                    Log.i(TAG, "eval crop photo=${photo.name} ${detector.labels[d.label].name} ${"%.2f".format(d.score)} ms=$ms looks=$top " +
                        queries.indices.joinToString(" ") { i ->
                            val s = closeness(q[i], v)
                            "q$i=${"%.3f".format(s)}/${"%.2f".format(toLabels.count { it < s } / toLabels.size.toFloat())}"
                        })
                }
            }
        }
        embedder.close()
        Log.i(TAG, "eval picture_names agreed_with_yolox=$agreed of $named")
        // What the camera highlights for these questions (S14, 2026-10-24).
        assertEquals(setOf("bottle", "wine glass", "cup"), matched[0])
        assertEquals(setOf("chair", "bench", "couch"), matched[2])
        assertEquals(setOf("laptop", "mouse", "keyboard"), matched[3])
        assertTrue("something to eat should find food", matched[1].containsAll(setOf("sandwich", "pizza")))
        assertEquals("none of the labels are keys", emptySet<String>(), matched[4])
    }

    private companion object {
        const val TAG = "AnuS14"
    }
}
