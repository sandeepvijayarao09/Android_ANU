package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.brain.litertlm.TurnSpeed
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedLogTest {

    @Test
    fun `the speed line has counts and rates only`() {
        val line = speedLine(BrainId.PRO, "CPU", TurnSpeed(read = 26, readPerSecond = 55.14, firstTokenMs = 1234, written = 129, writtenPerSecond = 7.06))
        assertEquals("Pro on CPU read 26 tokens at 55.1 tok/s, first token after 1234 ms, wrote 129 at 7.1 tok/s", line)
    }

    @Test
    fun `the speed line says whether the chat was read again, naming only what changed`() {
        val line = speedLine(BrainId.PRO, "CPU", TurnSpeed(read = 1800, readPerSecond = 60.0, firstTokenMs = 30_000, written = 40, writtenPerSecond = 6.0, conversation = "rebuilt (system, tools)"))
        assertEquals("Pro on CPU read 1800 tokens at 60.0 tok/s, first token after 30000 ms, wrote 40 at 6.0 tok/s, conversation rebuilt (system, tools)", line)
    }

    @Test
    fun `a turn with no output says so`() {
        val line = speedLine(BrainId.BASE, "CPU", TurnSpeed(read = 10, readPerSecond = 20.0, firstTokenMs = -1, written = 0, writtenPerSecond = 0.0))
        assertEquals("Base on CPU read 10 tokens at 20.0 tok/s, no token, wrote 0 at 0.0 tok/s", line)
    }
}
