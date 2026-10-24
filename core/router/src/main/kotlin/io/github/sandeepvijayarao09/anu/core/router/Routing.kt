package io.github.sandeepvijayarao09.anu.core.router

import io.github.sandeepvijayarao09.anu.core.model.DataClass

/** Model tiers, lightest first (ARCHITECTURE §5). The order matters: escalation moves right. */
enum class Tier { BASE, PRO, CLOUD }

/**
 * The top tier's model as the UI names it (ADR-111): Gemini, or the user's own Mac when one is paired, which takes
 * Gemini's place. Either is off the phone, so the same consent and egress rules hold. [name] goes mid-sentence ("Use
 * [name] for this?"), [title] at the start of one, [where] says where the data goes, and [label] tags an answer.
 */
data class TopModel(val name: String, val title: String, val where: String, val label: String, val model: String, val consent: String) {
    companion object {
        val GEMINI = TopModel(
            name = "Gemini",
            title = "Gemini",
            where = "Gemini (Google)",
            label = "Cloud · Gemini",
            model = "Gemini",
            consent = "This needs the cloud model. Your message goes to Google with email addresses, phone and card numbers " +
                "replaced, and the send is recorded in the Privacy report.",
        )

        fun mac(address: String) = TopModel(
            name = "your Mac",
            title = "Your Mac",
            where = "your Mac ($address)",
            label = "Your Mac · Gemma 4 26B",
            model = "Gemma 4 26B on your Mac",
            consent = "This needs a bigger model. Your message goes to Gemma 4 26B on your Mac over your Wi-Fi, with email " +
                "addresses, phone and card numbers replaced, and the send is recorded in the Privacy report.",
        )

        /** Which one the app uses now. Set by the app when a Mac is paired or unpaired. */
        @Volatile var current: TopModel = GEMINI
    }
}

/** The skill a request belongs to (§10). */
/**
 * [VOICE_NOTE] is a voice note of the user's own that the typed words don't send elsewhere (EXP-3, ADR-078). [PHONE]
 * is a request that uses the phone's controls but is more than one whole command, which runs with no model (G6).
 * [APPS] is a request that names another app to do something in, by tapping in it (ADR-098). [BRIEF] is a daily brief and
 * [AGENTS] a request across two or more of the phone's areas, which ADK's agents carry out (ADR-104).
 */
enum class Skill { CHAT, WRITING, EMAIL, SCHEDULING, MESSAGES, BRIEF, MEMORY, SHOPPING, VOICE_NOTE, PHONE, APPS, AGENTS }

/**
 * Whether a skill may send personal data to the cloud. ASK is the default (OD-6).
 * Turning the cloud off entirely is a device setting: CLOUD is then not in [DeviceState.availableTiers].
 */
enum class CloudPolicy { ASK, ALLOWED, NEVER }

data class DeviceState(
    val online: Boolean,
    /** Tiers that can run right now: on-device models installed and loadable, and Cloud with a key set. */
    val availableTiers: Set<Tier>,
    /** Why the phone is guarded right now, if it is: heat forecast, or a low battery (§6, Stage A; ADR-072). */
    val guard: Guard? = null,
    /**
     * Base runs on the phone's TPU (ADR-103), several times faster than Pro on the CPU, so a request scored for Pro
     * goes to Base when it fits Base's budget. A failed answer still moves up to Pro (Stage C).
     */
    val baseOnTpu: Boolean = false,
    /** Pro runs on the GPU, loaded beside Base on the TPU (ADR-106), so a request scored for Pro stays on Pro. */
    val proOnGpu: Boolean = false,
) {
    /** Whether the top model (Gemini, or the user's Mac) could be asked right now, as far as the phone knows. */
    val cloudAllowed: Boolean
        get() = online && Tier.CLOUD in availableTiers
}

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
    /** A photo is in the model's view. Photos never leave the phone, so the cloud is out (MEDIA-1, ADR-075). */
    val hasPhotos: Boolean = false,
    /** A voice note is in the model's view. Recordings and their words never leave the phone (MEDIA-1, ADR-078). */
    val hasVoiceNote: Boolean = false,
    /** A screen the user asked about is in the model's view. Screens never leave the phone (MEDIA-1, ADR-083). */
    val hasScreen: Boolean = false,
    /** A PDF the user attached is in the model's view. PDFs and their text never leave the phone (MEDIA-1, ADR-095). */
    val hasDocument: Boolean = false,
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
    /** The phone's guard when this was decided. A failed turn under a guard never moves to the cloud by itself. */
    val guard: Guard? = null,
    /** True when [guard] changed this decision: Base instead of Pro, or Pro with a shorter answer (ADR-072). */
    val guarded: Boolean = false,
    /** The longest answer allowed, in tokens, or null for the tier's own limit. */
    val maxOutputTokens: Int? = null,
)

sealed interface RouteResult {
    data class Routed(val decision: RouteDecision) : RouteResult

    data class Unavailable(val reasons: List<String>) : RouteResult
}
