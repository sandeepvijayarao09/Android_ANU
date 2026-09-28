package io.github.sandeepvijayarao09.anu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereLoopsTest {

    @Test
    fun `an idle sphere runs nothing, so an idle screen draws no frames (ADR-063)`() {
        assertTrue(sphereLoops(SphereState.IDLE, onScreen = true, still = false).isEmpty())
    }

    @Test
    fun `each state runs only its own animation`() {
        assertEquals(setOf(SphereLoop.WAVE), sphereLoops(SphereState.LISTENING, onScreen = true, still = false))
        assertEquals(setOf(SphereLoop.TURN), sphereLoops(SphereState.THINKING, onScreen = true, still = false))
        assertEquals(setOf(SphereLoop.PULSE), sphereLoops(SphereState.SPEAKING, onScreen = true, still = false))
    }

    @Test
    fun `nothing runs off screen or with reduced motion`() {
        SphereState.entries.forEach { state ->
            assertTrue("$state off screen", sphereLoops(state, onScreen = false, still = false).isEmpty())
            assertTrue("$state with reduced motion", sphereLoops(state, onScreen = true, still = true).isEmpty())
        }
    }
}
