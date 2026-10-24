package io.github.sandeepvijayarao09.anu.feature.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTextTest {

    @Test
    fun `a rewrite takes the selection, or else the text before the cursor`() {
        val selected = KeyboardText.forRewrite(FieldText(before = "Hi there. ", selected = " i has a cat "))!!
        assertEquals(EditMode.REPLACE_SELECTION, selected.mode)
        assertEquals("i has a cat", selected.input)

        val before = KeyboardText.forRewrite(FieldText(before = "\ni has a cat  "))!!
        assertEquals(EditMode.REPLACE_BEFORE, before.mode)
        assertEquals("\ni has a cat  ", before.source)
        assertEquals("i has a cat", before.input)

        assertNull(KeyboardText.forRewrite(FieldText(before = "   ")))
    }

    @Test
    fun `a replacement keeps the spaces around the text it replaces`() {
        val plan = KeyboardText.forRewrite(FieldText(before = "\ni has a cat  "))!!
        assertEquals("\nI have a cat.  ", KeyboardText.output(plan, "I have a cat."))
    }

    @Test
    fun `continue adds a sentence after the cursor, with a space only when it needs one`() {
        val plan = KeyboardText.forContinue(FieldText(before = "See you at 3."))!!
        assertEquals(EditMode.INSERT, plan.mode)
        assertEquals(" Bring the slides.", KeyboardText.output(plan, "Bring the slides."))
        assertEquals("Bring the slides.", KeyboardText.output(KeyboardText.forContinue(FieldText(before = "See you at 3. "))!!, "Bring the slides."))
        assertEquals(", then lunch.", KeyboardText.join("Meeting at 3", ", then lunch."))

        val kept = KeyboardText.forContinue(FieldText(before = "", selected = "See you at 3."))!!
        assertTrue(kept.keep)
        assertEquals("See you at 3. Bring the slides.", KeyboardText.output(kept, "Bring the slides."))

        assertNull(KeyboardText.forContinue(FieldText(before = "")))
    }

    @Test
    fun `reply replaces a started draft, or goes in at the cursor of an empty field`() {
        assertEquals(EditMode.REPLACE_BEFORE, KeyboardText.forReply(FieldText(before = "running late")).mode)
        val empty = KeyboardText.forReply(FieldText(before = ""))
        assertEquals(EditMode.INSERT, empty.mode)
        assertEquals("On my way!", KeyboardText.output(empty, "On my way!"))
    }

    @Test
    fun `a result goes in only while the field still holds what it was made from`() {
        val plan = KeyboardText.forRewrite(FieldText(before = "i has a cat"))!!
        assertTrue(KeyboardText.stillThere(plan, FieldText(before = "i has a cat")))
        assertFalse("the user typed more", KeyboardText.stillThere(plan, FieldText(before = "i has a cat and")))
        assertFalse("the user selected something", KeyboardText.stillThere(plan, FieldText(before = "i has a cat", selected = "cat")))

        val selection = KeyboardText.forRewrite(FieldText(before = "", selected = "i has"))!!
        assertTrue(KeyboardText.stillThere(selection, FieldText(before = "x", selected = "i has")))
        assertFalse(KeyboardText.stillThere(selection, FieldText(before = "x", selected = "")))

        val insert = KeyboardText.forReply(FieldText(before = ""))
        assertTrue(KeyboardText.stillThere(insert, FieldText(before = "anything")))
    }

    @Test
    fun `a long field is read from its first sentence break inside the window`() {
        val long = "x".repeat(3_000) + ". Second sentence starts here. " + "y".repeat(1_000)
        val tail = KeyboardText.tail(long)
        assertTrue(tail.length <= KeyboardText.READ_CHARS)
        val window = "a".repeat(100) + ". " + "b".repeat(KeyboardText.READ_CHARS - 102)
        assertEquals(" " + "b".repeat(KeyboardText.READ_CHARS - 102), KeyboardText.tail(window))
        assertEquals("short text", KeyboardText.tail("short text"))
    }

    @Test
    fun `a continuation that repeats its text loses the repeat`() {
        assertEquals("Bring the slides.", KeyboardText.withoutEcho("See you at 3.", "See you at 3. Bring the slides."))
        assertEquals("Bring the slides.", KeyboardText.withoutEcho("See you at 3.", "Bring the slides."))
    }
}
