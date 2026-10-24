package io.github.sandeepvijayarao09.anu.connector.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** How phone controls say what they did (ADR-076). */
class PhoneWordsTest {

    @Test
    fun `durations read as words`() {
        assertEquals("10 minutes", duration(600))
        assertEquals("1 hour 30 minutes", duration(5_400))
        assertEquals("45 seconds", duration(45))
        assertEquals("1 minute 5 seconds", duration(65))
    }

    @Test
    fun `alarm times read on a twelve-hour clock`() {
        assertEquals("7:00 AM", clock(7, 0))
        assertEquals("6:45 PM", clock(18, 45))
        assertEquals("12:00 AM", clock(0, 0))
        assertEquals("12:30 PM", clock(12, 30))
    }

    @Test
    fun `brightness follows the slider's curve`() {
        assertEquals(255, brightnessLevel(100))
        assertEquals(55, brightnessLevel(50))
        assertEquals(1, brightnessLevel(0))
    }
}
