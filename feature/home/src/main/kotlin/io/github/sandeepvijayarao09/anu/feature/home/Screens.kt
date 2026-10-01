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
    /** Ready to show the model: [screen]'s picture has its codes masked. [chat] is the chat it shows, when Anu knows it. */
    data class Ready(val screen: Screen, val chat: ScreenChat? = null) : ScreenLook

    /** It can't be used, and why, in words for the card. */
    data class Unavailable(val reason: String) : ScreenLook
}

/**
 * The chat a screen shows, among the chats Anu has seen in notifications (EXP-2, ADR-092): what a reply drafted about it
 * would answer. [app] is the messaging app's name.
 */
data class ScreenChat(val key: String, val title: String, val app: String)

/** Whether a chat can still be answered from Anu: its notification, with its reply button, is still showing (ADR-031). */
fun interface ReplyTargets {
    fun canReply(chatKey: String): Boolean
}
