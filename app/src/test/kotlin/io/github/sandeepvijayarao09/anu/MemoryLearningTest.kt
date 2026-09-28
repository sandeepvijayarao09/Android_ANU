package io.github.sandeepvijayarao09.anu

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLearningTest {

    @Test
    fun `learning waits until the phone is idle and charging, so it never slows the app in use (ADR-064)`() {
        val constraints = MemoryLearning.constraints()
        assertTrue(constraints.requiresDeviceIdle())
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
    }
}
