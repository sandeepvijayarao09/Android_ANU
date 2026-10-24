package io.github.sandeepvijayarao09.anu.core.router

import io.github.sandeepvijayarao09.anu.core.model.DataClass

/**
 * Router v1 (ARCHITECTURE §6).
 * - Stage A: hard constraints decide which tiers are allowed.
 * - Stage B: [ComplexityScorer] picks the lowest adequate tier among them. While the phone is guarded (hot, or
 *   low on battery), a request scored for Pro goes to Base when it fits, and Pro gives shorter answers (ADR-072).
 *   So does a request scored for Pro while Base runs on the TPU and Pro on the CPU (ADR-103); with Pro on the GPU, it
 *   stays on Pro (ADR-106).
 * - Stage C: [escalate] moves one tier up when an answer fails validation, but never to the cloud by itself
 *   while the phone is guarded: Retry offers that instead.
 */
class Router(
    private val scorer: ComplexityScorer = ComplexityScorer(),
    private val cloudPolicy: (Skill) -> CloudPolicy = { CloudPolicy.ASK },
) {

    fun route(request: RouteRequest, device: DeviceState): RouteResult {
        val reasons = mutableListOf<String>()
        val allowed = allowedTiers(request, device, reasons)
        if (allowed.isEmpty()) return RouteResult.Unavailable(reasons + "no model tier is available")

        val scored = scorer.score(request)
        val guard = device.guard
        request.pinnedTier?.let { pinned ->
            if (pinned in allowed) {
                return RouteResult.Routed(decide(request, pinned, allowed, scored.score, reasons + "pinned to $pinned by the user", guard))
            }
            // Asking Gemini by name never quietly runs on the phone instead: it says why it can't (ADR-075).
            if (pinned == Tier.CLOUD) return RouteResult.Unavailable(reasons + "${TopModel.current.title} can't be asked here")
            reasons += "pinned tier $pinned is unavailable"
        }

        reasons += scored.reasons
        val candidates = Tier.entries.filter { it in allowed }
        var tier = candidates.firstOrNull { it >= scored.target } ?: candidates.last()
        if (tier != scored.target) reasons += "${scored.target} is unavailable, using $tier"
        var movedDown = false
        if (guard != null && tier == Tier.PRO && Tier.BASE in allowed && request.promptTokens <= scorer.budgets.base) {
            tier = Tier.BASE
            movedDown = true
            reasons += "${guard.reason}: Base instead of Pro"
        }
        if (device.baseOnTpu && !device.proOnGpu && tier == Tier.PRO && Tier.BASE in allowed && request.promptTokens <= scorer.budgets.base) {
            tier = Tier.BASE
            reasons += "Base runs on the TPU: Base instead of Pro"
        }
        return RouteResult.Routed(decide(request, tier, allowed, scored.score, reasons, guard, movedDown))
    }

    /** The next tier up after [from] failed, or null when none is left (§6, Stage C). */
    fun escalate(request: RouteRequest, from: RouteDecision, why: String): RouteDecision? {
        val next = Tier.entries.firstOrNull { it > from.tier && it in from.allowed } ?: return null
        // A guarded phone never quietly moves a failed turn off the phone (BATTERY-1). Retry offers it.
        if (next == Tier.CLOUD && from.guard != null) return null
        return decide(request, next, from.allowed, from.score, from.reasons + "escalated from ${from.tier}: $why", from.guard)
    }

    private fun allowedTiers(request: RouteRequest, device: DeviceState, reasons: MutableList<String>): Set<Tier> {
        val allowed = device.availableTiers.toMutableSet()
        val cloudBlock = when {
            !device.online -> "offline: on-device only"
            request.hasPersonalData && cloudPolicy(request.skill) == CloudPolicy.NEVER ->
                "cloud is off for personal data in ${request.skill}"
            DataClass.SENSITIVE in request.dataClasses && !request.cloudConsented -> "sensitive data: on-device only"
            // Consent can't open this: a photo is never sent (MEDIA-1).
            request.hasPhotos -> "a photo is in view: on-device only"
            request.hasVoiceNote -> "a voice note is in view: on-device only"
            request.hasScreen -> "a screen is in view: on-device only"
            request.hasDocument -> "a PDF is in view: on-device only"
            else -> null
        }
        if (cloudBlock != null && allowed.remove(Tier.CLOUD)) reasons += cloudBlock
        return allowed
    }

    /** [movedDown] says the guard sent a Pro request to Base. On Pro, a guard shortens the answer instead. */
    private fun decide(
        request: RouteRequest,
        tier: Tier,
        allowed: Set<Tier>,
        score: Int,
        reasons: List<String>,
        guard: Guard? = null,
        movedDown: Boolean = false,
    ): RouteDecision {
        val consent = when {
            tier != Tier.CLOUD || request.cloudConsented || !request.hasPersonalData -> Consent.NOT_NEEDED
            DataClass.SENSITIVE !in request.dataClasses && cloudPolicy(request.skill) == CloudPolicy.ALLOWED -> Consent.NOT_NEEDED
            else -> Consent.ASK
        }
        val limited = guard != null && tier == Tier.PRO
        return RouteDecision(
            tier, allowed, score, if (limited) reasons + "${guard!!.reason}: a shorter answer" else reasons, consent,
            guard = guard,
            guarded = movedDown || limited,
            maxOutputTokens = if (limited) GuardLimits.ANSWER_TOKENS else null,
        )
    }
}
