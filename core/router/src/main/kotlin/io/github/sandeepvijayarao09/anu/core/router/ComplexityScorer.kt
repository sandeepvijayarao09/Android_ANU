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
class ComplexityScorer(private val budgets: TierBudgets = TierBudgets()) {

    fun score(request: RouteRequest): Scored {
        val text = request.text.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0

        fun add(points: Int, why: String) {
            score += points
            reasons += if (points >= 0) "$why +$points" else "$why $points"
        }

        SKILL_WEIGHT.getValue(request.skill).takeIf { it > 0 }?.let { add(it, "skill ${request.skill}") }
        val actions = ACTIONS.count { it.containsMatchIn(text) }
        if (actions >= 2) add(2, "$actions actions")
        if (CHAIN.containsMatchIn(text)) add(1, "chained steps")
        if (REASONING.containsMatchIn(text)) add(1, "reasoning")
        if (LONG_FORM.containsMatchIn(text)) add(1, "long-form output")
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

    private companion object {
        val SKILL_WEIGHT = mapOf(
            Skill.CHAT to 0,
            Skill.MEMORY to 0,
            Skill.MESSAGES to 0,
            Skill.WRITING to 1,
            Skill.EMAIL to 1,
            Skill.BRIEF to 2,
            Skill.SCHEDULING to 2,
            Skill.SHOPPING to 1,
        )
        val ACTIONS = listOf(
            "find", "schedul", "book", "email", "send", "repl", "invit", "summar", "draft", "remind", "compar",
            "move", "cancel",
        ).map { Regex("\\b$it") }
        val CHAIN = Regex("\\b(and then|then|after that|afterwards)\\b")
        val REASONING = Regex("\\b(why|compar\\w*|pros and cons|trade-?offs?|plan(ning)?|analy[sz]\\w*|calculat\\w*|step by step|explain how)\\b")
        val LONG_FORM = Regex("\\b(essay|report|proposal|article|cover letter|detailed|in depth)\\b")
        val SHORT_TASK = Regex("\\b(one line|one sentence|short|quick|briefly|fix (the )?grammar|proofread|translate|rewrite)\\b")
    }
}
