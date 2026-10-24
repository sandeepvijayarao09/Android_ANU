package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The benchmark Google's AI Edge Gallery runs (its Benchmark screen, 1.0.20), so Anu's numbers on a phone can be set
 * beside Gallery's on the same phone and file: LiteRT-LM's own `benchmark()` with synthetic tokens, 256 read and 256
 * written by default, 3 runs, each in a fresh cache folder that is deleted afterwards. The first run's load includes
 * building the cache; the later ones are reported apart, as Gallery does.
 * Runner arguments: backend and modelPath (S1), and prefillTokens, decodeTokens and runs.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class GalleryParityBenchmarkTest {

    @Test
    fun runsGallerysBenchmark() {
        S1.assumeModelPresent()
        val args = InstrumentationRegistry.getArguments()
        val prefillTokens = args.getString("prefillTokens")?.toInt() ?: 256
        val decodeTokens = args.getString("decodeTokens")?.toInt() ?: 256
        val runs = args.getString("runs")?.toInt() ?: 3
        S1.report("gallery_config", "prefill=$prefillTokens decode=$decodeTokens runs=$runs")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(context.cacheDir, "benchmark_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val results = (0 until runs).map { i ->
                benchmark(
                    modelPath = S1.modelPath,
                    backend = S1.backend(),
                    prefillTokens = prefillTokens,
                    decodeTokens = decodeTokens,
                    cacheDir = cache.absolutePath,
                ).also {
                    S1.report(
                        "gallery_run_$i",
                        "init_ms=${(it.initTimeInSecond * 1000).toInt()} ttft_ms=${(it.timeToFirstTokenInSecond * 1000).toInt()} " +
                            "prefill_tok_s=${"%.1f".format(it.lastPrefillTokensPerSecond)} decode_tok_s=${"%.1f".format(it.lastDecodeTokensPerSecond)}",
                    )
                }
            }
            S1.report("gallery_first_init_ms", (results.first().initTimeInSecond * 1000).toInt())
            results.drop(1).takeIf { it.isNotEmpty() }?.let { later -> S1.report("gallery_later_init_ms", (later.map { it.initTimeInSecond }.average() * 1000).toInt()) }
            S1.report("gallery_prefill_tok_s", "%.1f".format(results.map { it.lastPrefillTokensPerSecond }.average()))
            S1.report("gallery_decode_tok_s", "%.1f".format(results.map { it.lastDecodeTokensPerSecond }.average()))
            S1.report("gallery_ttft_ms", (results.map { it.timeToFirstTokenInSecond }.average() * 1000).toInt())
            assertTrue("every run decoded", results.all { it.lastDecodeTokensPerSecond > 0 })
        } finally {
            cache.deleteRecursively()
        }
    }
}
