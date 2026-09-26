package io.github.sandeepvijayarao09.anu.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTest {

    @Test
    fun `the wake phrase is found at the start, and the request after it is kept`() {
        assertEquals("", WakePhrase.remainder("Hey Anu"))
        assertEquals("what's on my calendar?", WakePhrase.remainder("Hey Anu, what's on my calendar?"))
        assertEquals("text Maya I'm late", WakePhrase.remainder("Anu text Maya I'm late"))
        assertEquals("", WakePhrase.remainder("hey anew"))
        assertEquals("set a timer", WakePhrase.remainder("OK annu set a timer"))
    }

    @Test
    fun `ordinary speech doesn't wake Anu`() {
        assertNull(WakePhrase.remainder("a new message arrived"))
        assertNull(WakePhrase.remainder("anew we begin"))
        assertNull(WakePhrase.remainder("I told Anu about it"))
        assertNull(WakePhrase.remainder("hey honey"))
        assertNull(WakePhrase.remainder("anubis is a god"))
    }

    @Test
    fun `a streaming answer is spoken sentence by sentence, without markdown`() {
        val (first, at) = Sentences.next("Sure! Your **meeting** is at 3", 0, final = false)
        assertEquals(listOf("Sure!"), first)
        val (rest, end) = Sentences.next("Sure! Your **meeting** is at 3. Anything else?", at, final = false)
        assertEquals(listOf("Your meeting is at 3.", "Anything else?"), rest)
        assertEquals(Sentences.next("Sure! Your **meeting** is at 3. Anything else?", 0, final = false).second, end)
        val (tail, _) = Sentences.next("Here is the [link](https://example.com) to it", 0, final = true)
        assertEquals(listOf("Here is the link to it"), tail)
    }

    @Test
    fun `numbers with dots aren't sentence ends`() {
        assertEquals(listOf("It costs 3.50 today."), Sentences.next("It costs 3.50 today.", 0, final = false).first)
    }
}
