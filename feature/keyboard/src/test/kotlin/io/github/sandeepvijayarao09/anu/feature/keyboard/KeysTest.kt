package io.github.sandeepvijayarao09.anu.feature.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {

    @Test
    fun `every row of every page is as wide as the top row`() {
        KeyPage.entries.forEach { page ->
            val rows = Keys.rows(page, Shift.OFF)
            assertEquals(4, rows.size)
            rows.forEach { row -> assertEquals("$page", 10f, row.sumOf { it.weight.toDouble() }.toFloat(), 0.01f) }
        }
        listOf(KeyLayout.NUMBERS, KeyLayout.PHONE).forEach { layout ->
            Keys.pad(layout).forEach { row -> assertEquals(4f, row.sumOf { it.weight.toDouble() }.toFloat(), 0.01f) }
        }
    }

    @Test
    fun `letters follow shift, and every page has delete, the globe, space and enter`() {
        val upper = Keys.rows(KeyPage.LETTERS, Shift.ONCE).first().filterIsInstance<Key.Text>().joinToString("") { it.text }
        assertEquals("QWERTYUIOP", upper)
        KeyPage.entries.forEach { page ->
            val keys = Keys.rows(page, Shift.OFF).flatten()
            assertTrue(keys.any { it is Key.Backspace } && keys.any { it is Key.Globe } && keys.any { it is Key.Space } && keys.any { it is Key.Enter })
        }
    }

    @Test
    fun `shift is once, then locked on a quick second tap, and a letter releases a one-time shift`() {
        assertEquals(Shift.ONCE, ShiftRules.tap(Shift.OFF, 10_000))
        assertEquals(Shift.LOCKED, ShiftRules.tap(Shift.ONCE, 200))
        assertEquals(Shift.OFF, ShiftRules.tap(Shift.ONCE, 1_000))
        assertEquals(Shift.OFF, ShiftRules.tap(Shift.LOCKED, 100))
        assertEquals(Shift.OFF, ShiftRules.afterText(Shift.ONCE))
        assertEquals(Shift.LOCKED, ShiftRules.afterText(Shift.LOCKED))
        assertEquals(Shift.ONCE, ShiftRules.auto(Shift.OFF, capsNow = true))
        assertEquals(Shift.OFF, ShiftRules.auto(Shift.ONCE, capsNow = false))
        assertEquals(Shift.LOCKED, ShiftRules.auto(Shift.LOCKED, capsNow = false))
    }
}
