package io.github.sandeepvijayarao09.anu.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OneTimeCodesTest {

    @Test
    fun `verification codes are masked in their common shapes`() {
        assertEquals("Your code is ••••••. It expires in 10 minutes.", OneTimeCodes.mask("Your code is 482913. It expires in 10 minutes."))
        assertEquals("G-•••••• is your Google verification code", OneTimeCodes.mask("G-482913 is your Google verification code"))
        assertEquals("Use •••••• to sign in", OneTimeCodes.mask("Use 482 913 to sign in"))
        assertEquals("Your bank OTP: ••••••", OneTimeCodes.mask("Your bank OTP: 5521"))
    }

    @Test
    fun `ordinary messages keep their numbers`() {
        val lunch = "Lunch at 1? The table is booked for 4 people, confirmation 2026."
        assertEquals(lunch, OneTimeCodes.mask(lunch))
        assertFalse(OneTimeCodes.looksLikeVerification("Call me at 3, the address is 1204 Main St"))
    }
}
