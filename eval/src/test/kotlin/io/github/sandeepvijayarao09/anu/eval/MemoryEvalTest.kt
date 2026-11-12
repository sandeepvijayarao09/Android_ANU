package io.github.sandeepvijayarao09.anu.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory eval's JVM tier (§15, ADR-143), in CI: memory's own logic with a perfect, scripted extractor. Every
 * scenario that isn't a known gap must come out exactly right; the known gaps are reported, so the score shows how
 * far memory is from them.
 */
class MemoryEvalTest {
    @Test
    fun `every life that isn't a known gap comes out exactly right, and nothing forgotten is left behind`() {
        for ((name, set) in listOf("dev" to MemoryEvalSet.dev, "held out" to MemoryEvalSet.heldOut)) {
            val score = MemoryEval.run(set)
            println(score.report(name))
            val broken = score.scenarios.filter { !it.clean && it.scenario.gap == null }.map { it.scenario.name }
            assertTrue("$name: $broken", broken.isEmpty())
            assertEquals("$name: forgotten words left behind", 0, score.leaks)
        }
    }
}
