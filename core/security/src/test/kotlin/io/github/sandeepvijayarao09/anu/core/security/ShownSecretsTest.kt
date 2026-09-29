package io.github.sandeepvijayarao09.anu.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class ShownSecretsTest {

    @Test
    fun `a code in an answer is masked, even when the code is named after it or only in the question`() {
        assertEquals("Your verification code is ••••••. Don't share it.", ShownSecrets.mask("Your verification code is 482913. Don't share it.", "What does this say?"))
        assertEquals("G-•••••• is your Google verification code.", ShownSecrets.mask("G-482913 is your Google verification code.", "What's this?"))
        assertEquals("It's ••••••.", ShownSecrets.mask("It's 482 913.", "What's the code in this screenshot?"))
    }

    @Test
    fun `a labelled password or PIN is masked, and ordinary numbers are left alone`() {
        assertEquals("The note says: password: ••••••. Keep it safe.", ShownSecrets.mask("The note says: password: Hunter2!. Keep it safe.", "What does this say?"))
        assertEquals("Her PIN is ••••••.", ShownSecrets.mask("Her PIN is 4821.", "Read this"))
        assertEquals("The concert is on June 5, 2026, in room 1204.", ShownSecrets.mask("The concert is on June 5, 2026, in room 1204.", "When is it?"))
        assertEquals("The password field is empty.", ShownSecrets.mask("The password field is empty.", "What's wrong?"))
    }

    @Test
    fun `a code found on the screen is masked wherever the answer repeats it, however it's written`() {
        // The emulator's Files app: Gemma called the file "482913.txt", with no word about a code.
        assertEquals("One file is visible: a text document named ••••••.txt.", ShownSecrets.mask("One file is visible: a text document named 482913.txt.", "What is on my screen?", listOf("482913")))
        assertEquals("It reads •••••• and ••••••.", ShownSecrets.mask("It reads 482 913 and 482-913.", "What is this?", listOf("482913")))
        assertEquals("the year 2026 isn't a known code", "Downloads, 2026.", ShownSecrets.mask("Downloads, 2026.", "What is this?", listOf("482913")))
    }
}
