package io.github.sandeepvijayarao09.anu.feature.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

enum class KeyLayout { LETTERS, NUMBERS, PHONE }

/** What the enter key does: a new line, or the field's own action, such as Search or Send. */
enum class EnterKey(val label: String, val actionId: Int) {
    NEWLINE("Return", EditorInfo.IME_ACTION_NONE),
    GO("Go", EditorInfo.IME_ACTION_GO),
    SEARCH("Search", EditorInfo.IME_ACTION_SEARCH),
    SEND("Send", EditorInfo.IME_ACTION_SEND),
    NEXT("Next", EditorInfo.IME_ACTION_NEXT),
    PREVIOUS("Previous", EditorInfo.IME_ACTION_PREVIOUS),
    DONE("Done", EditorInfo.IME_ACTION_DONE),
}

/**
 * What Anu's keyboard may do in one field (ADR-109), from the field's own [EditorInfo] alone: nothing typed is read to decide.
 * - [private]: a password, or a field whose app asked keyboards not to learn from it, as incognito tabs do
 *   (`IME_FLAG_NO_PERSONALIZED_LEARNING`). Anu then reads nothing from it, not even the caps mode, and offers no AI.
 * - [ai]: the AI strip is offered. Only in ordinary text fields: not in private, number, address or link fields, nor in
 *   fields that take raw key events.
 * - [chat]: the field looks like a chat's, so Reply is offered first.
 */
data class FieldPolicy(
    val layout: KeyLayout,
    val private: Boolean,
    val ai: Boolean,
    val chat: Boolean,
    val autoCaps: Boolean,
    val enter: EnterKey,
) {
    companion object {
        private val PASSWORDS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        private val ADDRESSES = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        private const val CAPS = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS

        /** [knownChat]: Anu has seen a chat from this field's app in its notifications (ADR-031). */
        fun of(inputType: Int, imeOptions: Int, knownChat: Boolean = false): FieldPolicy {
            val kind = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val password = (kind == InputType.TYPE_CLASS_TEXT && variation in PASSWORDS) ||
                (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val private = password || imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            val text = kind == InputType.TYPE_CLASS_TEXT
            val ai = !private && text && variation !in ADDRESSES
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            return FieldPolicy(
                layout = when (kind) {
                    InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> KeyLayout.NUMBERS
                    InputType.TYPE_CLASS_PHONE -> KeyLayout.PHONE
                    else -> KeyLayout.LETTERS
                },
                private = private,
                ai = ai,
                chat = ai && (variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE || action == EditorInfo.IME_ACTION_SEND || knownChat),
                autoCaps = !private && text && inputType and CAPS != 0,
                enter = enterKey(inputType, imeOptions),
            )
        }

        private fun enterKey(inputType: Int, imeOptions: Int): EnterKey {
            val multiLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 && multiLine) return EnterKey.NEWLINE
            return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO -> EnterKey.GO
                EditorInfo.IME_ACTION_SEARCH -> EnterKey.SEARCH
                EditorInfo.IME_ACTION_SEND -> EnterKey.SEND
                EditorInfo.IME_ACTION_NEXT -> EnterKey.NEXT
                EditorInfo.IME_ACTION_PREVIOUS -> EnterKey.PREVIOUS
                EditorInfo.IME_ACTION_DONE -> if (multiLine) EnterKey.NEWLINE else EnterKey.DONE
                else -> EnterKey.NEWLINE
            }
        }
    }
}
