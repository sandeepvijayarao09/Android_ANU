package io.github.sandeepvijayarao09.anu.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ExactCopiesTest {
    private val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    private val returned = listOf("""{"algorithm":"SHA-256","result":"$sha"}""")

    @Test
    fun `a code copied with a slip is put back as the tool gave it`() {
        val slipped = "2cf24dba5fb030a30e26e83b2ac5b9e2911b161e51fa725e333362938b9824"
        assertEquals("The hash is $sha.", ExactCopies.fix("The hash is $slipped.", returned))
    }

    @Test
    fun `words, exact copies and unrelated codes are left alone`() {
        assertEquals("The hash is $sha.", ExactCopies.fix("The hash is $sha.", returned))
        assertEquals("Your order is 9f86d081884c7d659a2feaa0c55ad015.", ExactCopies.fix("Your order is 9f86d081884c7d659a2feaa0c55ad015.", returned))
        assertEquals("Supercalifragilistic words stay.", ExactCopies.fix("Supercalifragilistic words stay.", returned))
        assertEquals("Nothing to fix.", ExactCopies.fix("Nothing to fix.", emptyList()))
    }

    @Test
    fun `a code the user gave is never rewritten`() {
        val theirs = sha.dropLast(1) + "5"
        val asked = "Is the SHA-256 of hello $theirs?"
        assertEquals("No: it's $sha, not $theirs.", ExactCopies.fix("No: it's $sha, not $theirs.", returned, said = listOf(asked)))
    }
}
