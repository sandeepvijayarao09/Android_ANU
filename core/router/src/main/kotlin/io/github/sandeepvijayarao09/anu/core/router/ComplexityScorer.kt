package io.github.sandeepvijayarao09.anu.core.router

/**
 * Per-tier prompt budgets, including the system prompt, memory and tool definitions (§7.3). They come
 * from the on-device context, less room for the answer and tool results (ADR-040), so a prompt the
 * router sends to a device tier fits it. The defaults match today's 4,096-token engines. Base gets
 * less because smaller models handle long prompts worse; that is a hypothesis until S1 on the phone.
 */
data class TierBudgets(val base: Int = 2_048, val pro: Int = 2_560) {
    companion object {
        /** Room kept for the answer and the turn's tool results. */
        const val ANSWER_RESERVE = 1_536

        fun forContext(contextTokens: Int, reserve: Int = ANSWER_RESERVE): TierBudgets {
            val pro = contextTokens - reserve
            require(pro > 0) { "a $contextTokens-token context leaves no room for a prompt" }
            return TierBudgets(base = pro * 4 / 5, pro = pro)
        }
    }
}

data class Scored(val target: Tier, val score: Int, val reasons: List<String>)

/**
 * Stage B of the router: transparent weighted rules (ARCHITECTURE §6). Every rule that fires adds a
 * reason, so each decision can be explained. A learned classifier replaces these weights in v2, and
 * is evaluated on a held-out set it was not tuned on (ADR-010).
 */
class ComplexityScorer(val budgets: TierBudgets = TierBudgets()) {

    fun score(request: RouteRequest): Scored {
        val text = request.text.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0

        fun add(points: Int, why: String) {
            score += points
            reasons += if (points >= 0) "$why +$points" else "$why $points"
        }

        weight(request.skill).takeIf { it > 0 }?.let { add(it, "skill ${request.skill}") }
        val actions = ACTIONS.count { it.containsMatchIn(text) }
        if (actions >= 2) add(2, "$actions actions")
        if (CHAIN.containsMatchIn(text)) add(1, "chained steps")
        // Reasoning: 2 for the first cue, 1 for each more, at most 4 (ADR-084). Explaining, advising, comparing and
        // planning are where Base's answers fell short on the router eval.
        val cues = REASONING.count { it.containsMatchIn(text) }
        if (cues > 0) add(minOf(4, cues + 1), "reasoning ($cues)")
        if (DURATION.containsMatchIn(text) && WHEN.containsMatchIn(text)) add(2, "time arithmetic")
        if (SUMMARY.containsMatchIn(text)) add(2, "summary")
        // Long-form output: 3 for each strong sign, at most 6, so an essay or a detailed report can reach the cloud.
        val long = LONG_FORM.count { it.containsMatchIn(text) }
        if (long > 0) add(minOf(6, long * 3), "long-form output ($long)")
        if (SHORT_TASK.containsMatchIn(text)) add(-1, "short task")

        // Cloud needs a high score on purpose: on-device is the default (P1).
        var target = when {
            score <= 1 -> Tier.BASE
            score <= 5 -> Tier.PRO
            else -> Tier.CLOUD
        }
        if (request.promptTokens > budgets.pro) {
            target = Tier.CLOUD
            reasons += "prompt of ${request.promptTokens} tokens exceeds the on-device budget"
        } else if (request.promptTokens > budgets.base && target == Tier.BASE) {
            target = Tier.PRO
            reasons += "prompt of ${request.promptTokens} tokens exceeds the Base budget"
        }
        return Scored(target, score, reasons)
    }

    /** A `when`, so a new skill can't be left without a weight. */
    private fun weight(skill: Skill): Int = when (skill) {
        Skill.CHAT, Skill.MEMORY, Skill.MESSAGES, Skill.PHONE -> 0
        // One call that names the app and the goal; the steps in the app are the task's own model calls (ADR-098).
        Skill.APPS -> 0
        // Each specialist and workflow step is its own call (ADR-104); the coordinator needs Pro to plan them.
        Skill.AGENTS -> 2
        Skill.WRITING, Skill.EMAIL, Skill.SHOPPING -> 1
        // A voice note usually holds several things to do, each with a date to work out (ADR-078).
        Skill.BRIEF, Skill.SCHEDULING, Skill.VOICE_NOTE -> 2
    }

    private companion object {
        val ACTIONS = listOf(
            "find", "schedul", "book", "email", "send", "repl", "invit", "summar", "draft", "remind", "compar",
            "move", "cancel", "suggest",
        ).map { Regex("\\b$it") }
        val CHAIN = Regex("\\b(and then|then|after that|afterwards)\\b")

        /** Each a separate cue, counted once. */
        val REASONING = listOf(
            "why", "compar\\w*", "pros and cons", "trade-?offs?", "plan(ning)?", "analy[sz]\\w*", "calculat\\w*", "step by step",
            "explain\\w*", "difference between", "how (does|can|should) ", "ways to", "tips (for|to|on)", "what should i",
        ).map { Regex("\\b$it\\b") }

        /** A length of time, with a question about when, as in "takes 2 hours 35 minutes. When does it arrive?" */
        val DURATION = Regex("\\b\\d+\\s*(hours?|hrs?|minutes?|mins?)\\b")
        val WHEN = Regex("\\b(when (does|do|will|is|am|are)|what time)\\b")
        val SUMMARY = Regex("\\bsummar(y|ies|i[sz]e\\w*)\\b")

        /** Strong signs of a long answer, each counted once. */
        val LONG_FORM = listOf(
            "essay", "report", "proposal", "article", "cover letter", "detailed", "in[- ]depth",
            "in detail", "comprehensive", "research", "with sources", "\\d[\\d,]{2,}[- ]words?",
        ).map { Regex("\\b$it\\b") }
        val SHORT_TASK = Regex("\\b(one line|one sentence|short|quick|briefly|fix (the )?grammar|proofread|translate|rewrite)\\b")
    }
}
