package io.github.sandeepvijayarao09.anu.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenCalibrationTest {

    @Test
    fun `it starts from the raw estimate and takes the first real count as is`() {
        val saved = mutableListOf<Double>()
        val calibration = TokenCalibration(onChange = { saved += it })
        assertEquals(1.05, calibration.scale, 1e-9) // the raw estimate, with a margin
        calibration.observe(TokenSample(estimated = 1_000, counted = 1_200, onDevice = true))
        assertEquals(1.2, calibration.ratio, 1e-9)
        assertEquals(1.26, calibration.scale, 1e-9)
        assertEquals(listOf(1.2), saved)
    }

    @Test
    fun `later counts are smoothed in, and outliers are clamped`() {
        val calibration = TokenCalibration(initial = 1.2)
        calibration.observe(TokenSample(1_000, 800, onDevice = true))
        assertEquals(1.2 + 0.3 * (0.8 - 1.2), calibration.ratio, 1e-9)
        val wild = TokenCalibration(initial = 1.0)
        wild.observe(TokenSample(1_000, 50_000, onDevice = true))
        assertEquals(1.0 + 0.3 * (2.5 - 1.0), wild.ratio, 1e-9)
        assertEquals(2.5, TokenCalibration(initial = 9.0).ratio, 1e-9)
    }

    @Test
    fun `cloud counts and small turns teach nothing`() {
        val calibration = TokenCalibration()
        calibration.observe(TokenSample(1_000, 2_000, onDevice = false)) // Gemini's tokenizer
        calibration.observe(TokenSample(50, 90, onDevice = true)) // template markers dominate
        calibration.observe(TokenSample(1_000, 0, onDevice = true))
        assertEquals(1.0, calibration.ratio, 1e-9)
    }

    @Test
    fun `NONE takes raw estimates as they are`() {
        TokenCalibration.NONE.observe(TokenSample(1_000, 2_000, onDevice = true))
        assertEquals(1.0, TokenCalibration.NONE.scale, 0.0)
    }
}
