package io.github.sandeepvijayarao09.anu.eval

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router eval (ARCHITECTURE §15, TODO-2) on both splits. Each prints its report, and each must do at least as
 * well as the router did when its floor was set: a routing change that makes more requests land on the wrong tier,
 * or detects fewer skills, fails here. Raise a floor when the router gets better; never lower one to pass.
 */
class RouterEvalTest {

    private fun check(split: String, tiers: Int, skills: Int) {
        val score = RouterEval.score(RouterEval.load(split))
        println(score.report(split))
        assertTrue("$split: ${score.tierRight} tiers right, floor $tiers", score.tierRight >= tiers)
        assertTrue("$split: ${score.skillRight} skills right, floor $skills", score.skillRight >= skills)
    }

    /** First run 37 of 60 tiers, 55 of 60 skills; raised after ADR-084's tuning on this split, 2026-09-29. */
    @Test
    fun `the dev split`() = check("dev", tiers = 52, skills = 56)

    /** First run 23 of 40 tiers, 36 of 40 skills; after ADR-084, which never looked at it, 33 and 36. */
    @Test
    fun `the held-out split`() = check("heldout", tiers = 33, skills = 36)
}
