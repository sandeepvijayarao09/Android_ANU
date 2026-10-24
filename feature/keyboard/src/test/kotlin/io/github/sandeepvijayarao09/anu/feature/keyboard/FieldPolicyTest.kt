package io.github.sandeepvijayarao09.anu.feature.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun `password fields are private, with no AI and no caps mode read`() {
        listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        ).forEach { type ->
            val policy = FieldPolicy.of(type, EditorInfo.IME_ACTION_DONE, knownChat = true)
            assertTrue("$type is private", policy.private)
            assertFalse(policy.ai)
            assertFalse(policy.chat)
            assertFalse(policy.autoCaps)
        }
    }

    @Test
    fun `a field that asks for no learning, as incognito tabs do, is private`() {
        val policy = FieldPolicy.of(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_GO)
        assertTrue(policy.private)
        assertFalse(policy.ai)
        assertEquals(EnterKey.GO, policy.enter)
    }

    @Test
    fun `ordinary text gets the AI strip, and a chat's field gets Reply`() {
        val plain = FieldPolicy.of(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, EditorInfo.IME_ACTION_SEARCH)
        assertTrue(plain.ai && !plain.private && plain.autoCaps)
        assertFalse(plain.chat)
        assertEquals(EnterKey.SEARCH, plain.enter)

        assertTrue(FieldPolicy.of(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, 0).chat)
        assertTrue(FieldPolicy.of(text, EditorInfo.IME_ACTION_SEND).chat)
        assertTrue("an app whose chats Anu has seen", FieldPolicy.of(text, 0, knownChat = true).chat)
    }

    @Test
    fun `addresses, links, numbers and raw fields get no AI but aren't private`() {
        listOf(
            text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            text or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_NULL,
        ).forEach { type ->
            val policy = FieldPolicy.of(type, 0)
            assertFalse("$type has no AI", policy.ai)
            assertFalse(policy.private)
        }
        assertEquals(KeyLayout.NUMBERS, FieldPolicy.of(InputType.TYPE_CLASS_NUMBER, 0).layout)
        assertEquals(KeyLayout.NUMBERS, FieldPolicy.of(InputType.TYPE_CLASS_DATETIME, 0).layout)
        assertEquals(KeyLayout.PHONE, FieldPolicy.of(InputType.TYPE_CLASS_PHONE, 0).layout)
        assertEquals(KeyLayout.LETTERS, FieldPolicy.of(text or InputType.TYPE_TEXT_VARIATION_URI, 0).layout)
    }

    @Test
    fun `enter makes a new line in multi-line fields and runs the field's action otherwise`() {
        val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(EnterKey.NEWLINE, FieldPolicy.of(multi, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION).enter)
        assertEquals(EnterKey.NEWLINE, FieldPolicy.of(multi, EditorInfo.IME_ACTION_DONE).enter)
        assertEquals(EnterKey.SEND, FieldPolicy.of(multi, EditorInfo.IME_ACTION_SEND).enter)
        assertEquals(EnterKey.DONE, FieldPolicy.of(text, EditorInfo.IME_ACTION_DONE).enter)
        assertEquals(EnterKey.NEXT, FieldPolicy.of(text, EditorInfo.IME_ACTION_NEXT).enter)
        assertEquals(EnterKey.NEWLINE, FieldPolicy.of(text, EditorInfo.IME_ACTION_UNSPECIFIED).enter)
    }
}
