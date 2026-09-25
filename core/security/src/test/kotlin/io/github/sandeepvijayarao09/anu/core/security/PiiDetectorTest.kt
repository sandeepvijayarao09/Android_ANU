package io.github.sandeepvijayarao09.anu.core.security

import io.github.sandeepvijayarao09.anu.core.model.DataClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiDetectorTest {

    private fun kinds(text: String) = PiiDetector.find(text).map { it.kind to it.value }

    @Test
    fun `finds emails and phone numbers`() {
        assertEquals(
            listOf(PiiKind.EMAIL to "maya@example.com", PiiKind.PHONE to "+1 (617) 555-0123"),
            kinds("Email maya@example.com or call +1 (617) 555-0123."),
        )
    }

    @Test
    fun `dates, times and short numbers are not phones`() {
        assertTrue(kinds("Meet on 2026-09-25 from 2-4 pm, room 1204, order 55512").isEmpty())
    }

    @Test
    fun `an ID number stays one match instead of being split`() {
        assertEquals(listOf(PiiKind.NATIONAL_ID to "123-45-6789"), kinds("SSN 123-45-6789"))
    }

    @Test
    fun `cards need a valid checksum and win over phone-shaped runs`() {
        assertEquals(listOf(PiiKind.CARD to "4111 1111 1111 1111"), kinds("card 4111 1111 1111 1111"))
        assertTrue(kinds("ref 4111 1111 1111 1112").none { it.first == PiiKind.CARD })
    }

    @Test
    fun `finds IBANs and reports sensitive data classes`() {
        val text = "Pay DE89 3704 0044 0532 0130 00 today"
        assertEquals(listOf(PiiKind.IBAN to "DE89 3704 0044 0532 0130 00"), kinds(text))
        assertEquals(setOf(DataClass.SENSITIVE), PiiDetector.dataClasses(text))
    }
}
