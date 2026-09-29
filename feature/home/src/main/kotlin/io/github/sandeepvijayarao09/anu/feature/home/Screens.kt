package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.model.Screen

/** The screen under the assistant card, for a question about it (EXP-2, ADR-083). */
interface ScreenSource {
    /** Whether this summon was handed a screen at all, so the card can offer "Use this screen". */
    fun offered(): Boolean

    /** The screen with any codes masked, or why it can't be used. Called only when the user asks about it. */
    suspend fun screen(): ScreenLook
}

/** What the card can do with the screen. */
sealed interface ScreenLook {
    /** Ready to show the model: [screen]'s picture has its codes masked. */
    data class Ready(val screen: Screen) : ScreenLook

    /** It can't be used, and why, in words for the card. */
    data class Unavailable(val reason: String) : ScreenLook
}
