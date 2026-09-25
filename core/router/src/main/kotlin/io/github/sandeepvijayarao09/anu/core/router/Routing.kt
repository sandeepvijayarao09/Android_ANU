package io.github.sandeepvijayarao09.anu.core.router

import io.github.sandeepvijayarao09.anu.core.model.DataClass

/** Model tiers, lightest first (ARCHITECTURE §5). The order matters: escalation moves right. */
enum class Tier { BASE, PRO, CLOUD }

/** The skill a request belongs to (§10). */
enum class Skill { CHAT, WRITING, EMAIL, SCHEDULING, MESSAGES, BRIEF, MEMORY }

/**
 * Whether a skill may send personal data to the cloud. ASK is the default (OD-6).
 * Turning the cloud off entirely is a device setting: CLOUD is then not in [DeviceState.availableTiers].
 */
enum class CloudPolicy { ASK, ALLOWED, NEVER }

data class DeviceState(
    val online: Boolean,
    /** Tiers that can run right now: on-device models installed and loadable, and Cloud with a key set. */
    val availableTiers: Set<Tier>,
    /** Hot, or low on battery and not charging. The heavy on-device model is avoided (§6, Stage A). */
    val constrained: Boolean = false,
)

data class RouteRequest(
    val text: String,
    val skill: Skill = Skill.CHAT,
    /** Every data class present in the context that will be sent with this request. */
    val dataClasses: Set<DataClass> = emptySet(),
    /** Estimated size of the whole prompt, including the system prompt (§7.3). */
    val promptTokens: Int,
    /** Set by "Retry with Pro" and similar explicit user choices. */
    val pinnedTier: Tier? = null,
    /** The user has already approved sending this request to the cloud. */
    val cloudConsented: Boolean = false,
) {
    init {
        require(DataClass.SECRET !in dataClasses) { "Secrets never enter a model context (I1)" }
    }

    val hasPersonalData: Boolean
        get() = DataClass.PERSONAL in dataClasses || DataClass.SENSITIVE in dataClasses
}

enum class Consent { NOT_NEEDED, ASK }

data class RouteDecision(
    val tier: Tier,
    val allowed: Set<Tier>,
    val score: Int,
    /** Why this tier was chosen, in order. Shown in the UI and logged for router evals. */
    val reasons: List<String>,
    /** ASK means the user must approve before this request goes to the cloud (§12.2). */
    val consent: Consent,
)

sealed interface RouteResult {
    data class Routed(val decision: RouteDecision) : RouteResult

    data class Unavailable(val reasons: List<String>) : RouteResult
}
