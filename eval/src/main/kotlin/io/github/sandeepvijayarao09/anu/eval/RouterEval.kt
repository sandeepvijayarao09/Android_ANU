package io.github.sandeepvijayarao09.anu.eval

import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.RouteResult
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.SkillDetector
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.security.PiiDetector

/** One made-up request, with the lightest tier that can do it well and the skill it needs (TODO-2). */
data class Labelled(val tier: Tier, val skill: Skill, val request: String)

/** A request the router placed differently from its label. */
data class Miss(val case: Labelled, val routed: Tier, val detected: Skill, val reasons: List<String>)

/**
 * How the router did on a split (ARCHITECTURE §15): tier accuracy, under-routing (a lighter tier than the request
 * needs, which risks a worse answer) and over-routing (a heavier one, which costs time or sends it away), skill
 * accuracy, and how long a decision takes.
 */
data class RouterScore(
    val total: Int,
    val tierRight: Int,
    val under: Int,
    val over: Int,
    val skillRight: Int,
    val misses: List<Miss>,
    val confusion: Map<Pair<Tier, Tier>, Int>,
    val microsPerDecision: Double,
) {
    val tierAccuracy: Double get() = tierRight.toDouble() / total
    val skillAccuracy: Double get() = skillRight.toDouble() / total

    /** A report to print: the numbers, the confusion table, and each miss with the router's reasons. */
    fun report(name: String): String = buildString {
        appendLine("Router eval, $name: $total requests")
        appendLine("  tier accuracy ${pct(tierAccuracy)} ($tierRight), under-routed $under, over-routed $over")
        appendLine("  skill accuracy ${pct(skillAccuracy)} ($skillRight)")
        appendLine("  %.1f µs a decision".format(microsPerDecision))
        appendLine("  labelled / routed: " + Tier.entries.joinToString(" ") { it.name })
        for (label in Tier.entries) {
            appendLine("  ${label.name.padEnd(6)} " + Tier.entries.joinToString(" ") { routed -> (confusion[label to routed] ?: 0).toString().padStart(routed.name.length) })
        }
        for (miss in misses) {
            appendLine("  miss: ${miss.case.tier}→${miss.routed} (${miss.case.skill}→${miss.detected}) \"${miss.case.request}\": ${miss.reasons.joinToString("; ")}")
        }
    }

    private fun pct(value: Double) = "%.0f%%".format(value * 100)
}

object RouterEval {
    /**
     * What a turn carries besides the request's own words, in tokens: the system prompt, the memory block and the
     * tools' specs. A fixed figure, since the eval routes requests, not whole chats.
     */
    const val FIXED_PROMPT_TOKENS = 900

    /** Every tier on hand, online, cool and charged: the router's choice alone. */
    val EVERYTHING = DeviceState(online = true, availableTiers = Tier.entries.toSet())

    /** A split from the classpath: "TIER<tab>SKILL<tab>request" per line, "#" for comments. */
    fun load(name: String): List<Labelled> {
        val text = requireNotNull(RouterEval::class.java.getResource("/router/$name.tsv")) { "no split $name" }.readText()
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (tier, skill, request) = line.split('\t', limit = 3)
            Labelled(Tier.valueOf(tier), Skill.valueOf(skill), request)
        }
    }

    /** Routes each request as a chat turn would (its detected skill, its personal data) and scores it against its label. */
    fun score(cases: List<Labelled>, router: Router = Router(), device: DeviceState = EVERYTHING): RouterScore {
        var right = 0
        var under = 0
        var over = 0
        var skills = 0
        var nanos = 0L
        val misses = mutableListOf<Miss>()
        val confusion = mutableMapOf<Pair<Tier, Tier>, Int>()
        for (case in cases) {
            val skill = SkillDetector.detect(listOf(case.request))
            if (skill == case.skill) skills++
            val request = RouteRequest(
                text = case.request,
                skill = skill,
                dataClasses = setOf(DataClass.PERSONAL) + PiiDetector.dataClasses(case.request),
                promptTokens = FIXED_PROMPT_TOKENS + case.request.length / 4,
            )
            val started = System.nanoTime()
            val result = router.route(request, device)
            nanos += System.nanoTime() - started
            val decision = (result as RouteResult.Routed).decision
            confusion.merge(case.tier to decision.tier, 1, Int::plus)
            when {
                decision.tier == case.tier -> right++
                decision.tier < case.tier -> under++.also { misses += Miss(case, decision.tier, skill, decision.reasons) }
                else -> over++.also { misses += Miss(case, decision.tier, skill, decision.reasons) }
            }
        }
        return RouterScore(cases.size, right, under, over, skills, misses, confusion, nanos / 1_000.0 / cases.size)
    }
}
