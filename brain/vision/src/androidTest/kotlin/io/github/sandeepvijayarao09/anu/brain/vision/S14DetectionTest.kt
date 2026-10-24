package io.github.sandeepvijayarao09.anu.brain.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import io.github.sandeepvijayarao09.anu.core.model.Frame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike S14 (ADR-111): YOLOX-Nano through LiteRT on this device. It finds the glasses on a dinner table (COCO val2017
 * 405691, 640 × 480, which scripts/live-camera-photos.sh pushes; it skips without it) and times detection, the
 * letterbox and decoding included, as the camera runs it.
 * Runner arguments: `width` for the frame's width (640 by default), and `backend=GPU`, `CPU` or `NPU` (spike S15, through
 * the app's native library folder); unset, the detector picks (the GPU on a phone, the CPU on an emulator).
 * Results: `adb logcat -d -s AnuS14`.
 */
@RunWith(AndroidJUnit4::class)
class S14DetectionTest {
    @Test
    fun findsPeopleAndTimesDetection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val backend = InstrumentationRegistry.getArguments().getString("backend")?.let { Accelerator.valueOf(it) }
        val photo = java.io.File("/data/local/tmp/anu/photos/live/000000405691.jpg")
        org.junit.Assume.assumeTrue("No photos: run scripts/live-camera-photos.sh", photo.exists())
        val bitmap = BitmapFactory.decodeFile(photo.path)
        // The camera's analysis frames are 640 × 480 or so; this one is shrunk the same way, unless `width` says.
        val width = InstrumentationRegistry.getArguments().getString("width")?.toInt() ?: 640
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, width, width * bitmap.height / bitmap.width, true)
        val frame = Frame(scaled.width, scaled.height, IntArray(scaled.width * scaled.height).also { scaled.getPixels(it, 0, scaled.width, 0, 0, scaled.width, scaled.height) })

        val loadStart = SystemClock.elapsedRealtime()
        YoloxDetector.open(instrumentation.targetContext.assets, backend, instrumentation.targetContext.applicationInfo.nativeLibraryDir).use { detector ->
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            repeat(WARM_UP) { detector.detect(frame) }
            val times = LongArray(RUNS)
            var found = detector.detect(frame)
            for (i in 0 until RUNS) {
                val start = SystemClock.elapsedRealtimeNanos()
                found = detector.detect(frame)
                times[i] = SystemClock.elapsedRealtimeNanos() - start
            }
            times.sort()
            val median = times[RUNS / 2] / 1e6
            val glasses = found.count { detector.labels[it.label].name == "wine glass" }
            Log.i(TAG, "s14 model=${detector.model} backend=${detector.backend} load_ms=$loadMs median_ms=${"%.1f".format(median)} " +
                "p90_ms=${"%.1f".format(times[RUNS * 9 / 10] / 1e6)} fps=${"%.1f".format(1000 / median)} found=${found.size} wine_glasses=$glasses")
            found.forEach { Log.i(TAG, "s14 ${detector.labels[it.label].name} ${"%.2f".format(it.score)} ${it.box}") }
            // 9 things, 4 of them wine glasses, on the emulator's CPU (2026-10-24).
            assertTrue("Expected at least 3 wine glasses, found $glasses", glasses >= 3)
        }
    }

    private companion object {
        const val TAG = "AnuS14"
        const val WARM_UP = 5
        const val RUNS = 30
    }
}
