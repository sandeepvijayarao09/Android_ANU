package io.github.sandeepvijayarao09.anu.core.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How often the endpointer ends a listen early, on the router eval's 100 made-up requests (ADR-129): each whole
 * request should sound finished (700 ms instead of 1.8 s), and each request cut at a word in its first half, where
 * a speaker might pause to think, should mostly not.
 */
class EndpointEvalTest {

    private fun requests(): List<String> = listOf("dev.tsv", "heldout.tsv").flatMap { name ->
        File("../../eval/src/main/resources/router/$name").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t').last() }
    }

    @Test
    fun `whole requests end quickly and half-spoken ones wait`() {
        val all = requests()
        val whole = all.count { Endpointer.sounds(it) == Endpointer.Sounds.FINISHED }
        val wholeWait = all.map { Endpointer.waitFor(it) }
        var cuts = 0
        var quick = 0
        var slow = 0
        for (request in all) {
            val words = request.split(' ')
            for (k in 1..words.size / 2) {
                cuts++
                when (Endpointer.waitFor(words.take(k).joinToString(" "))) {
                    Endpointer.QUICK_MS -> quick++
                    Endpointer.SLOW_MS -> slow++
                }
            }
        }
        println(
            "endpoint_eval requests=${all.size} whole_sound_finished=$whole whole_mean_wait_ms=${wholeWait.average().toInt()} " +
                "cuts=$cuts cuts_at_quick=$quick cuts_at_slow=$slow",
        )
        assertTrue("whole requests end quickly: $whole of ${all.size}", whole >= all.size * 95 / 100)
        // The rest of the cuts end at the middle wait. A cut that sounds finished after three words ("What's the capital")
        // can't be told from a whole request by its words, so this bounds how often a pause there ends the turn.
        assertTrue("half-spoken requests rarely end at the quick wait: $quick of $cuts", quick <= cuts * 45 / 100)
    }
}
