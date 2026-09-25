package io.github.sandeepvijayarao09.anu.core.router

import io.github.sandeepvijayarao09.anu.core.model.DataClass

/**
 * Router v1 (ARCHITECTURE §6).
 * - Stage A: hard constraints decide which tiers are allowed.
 * - Stage B: [ComplexityScorer] picks the lowest adequate tier among them.
 * - Stage C: [escalate] moves one tier up when an answer fails validation.
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
        request.pinnedTier?.let { pinned ->
            if (pinned in allowed) {
                return RouteResult.Routed(decide(request, pinned, allowed, scored.score, reasons + "pinned to $pinned by the user"))
            }
            reasons += "pinned tier $pinned is unavailable"
        }

        reasons += scored.reasons
        val candidates = Tier.entries.filter { it in allowed }
        val tier = candidates.firstOrNull { it >= scored.target } ?: candidates.last()
        if (tier != scored.target) reasons += "${scored.target} is unavailable, using $tier"
        return RouteResult.Routed(decide(request, tier, allowed, scored.score, reasons))
    }

    /** The next tier up after [from] failed, or null when none is left (§6, Stage C). */
    fun escalate(request: RouteRequest, from: RouteDecision, why: String): RouteDecision? {
        val next = Tier.entries.firstOrNull { it > from.tier && it in from.allowed } ?: return null
        return decide(request, next, from.allowed, from.score, from.reasons + "escalated from ${from.tier}: $why")
    }

    private fun allowedTiers(request: RouteRequest, device: DeviceState, reasons: MutableList<String>): Set<Tier> {
        val allowed = device.availableTiers.toMutableSet()
        val cloudBlock = when {
            !device.online -> "offline: on-device only"
            request.hasPersonalData && cloudPolicy(request.skill) == CloudPolicy.NEVER ->
                "cloud is off for personal data in ${request.skill}"
            DataClass.SENSITIVE in request.dataClasses && !request.cloudConsented -> "sensitive data: on-device only"
            else -> null
        }
        if (cloudBlock != null && allowed.remove(Tier.CLOUD)) reasons += cloudBlock
        if (device.constrained && Tier.PRO in allowed && allowed.size > 1) {
            allowed -= Tier.PRO
            reasons += "device is hot or low on battery: avoiding Pro"
        }
        return allowed
    }

    private fun decide(request: RouteRequest, tier: Tier, allowed: Set<Tier>, score: Int, reasons: List<String>): RouteDecision {
        val consent = when {
            tier != Tier.CLOUD || request.cloudConsented || !request.hasPersonalData -> Consent.NOT_NEEDED
            DataClass.SENSITIVE !in request.dataClasses && cloudPolicy(request.skill) == CloudPolicy.ALLOWED -> Consent.NOT_NEEDED
            else -> Consent.ASK
        }
        return RouteDecision(tier, allowed, score, reasons, consent)
    }
}
