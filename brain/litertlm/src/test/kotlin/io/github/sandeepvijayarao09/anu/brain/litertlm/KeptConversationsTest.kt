package io.github.sandeepvijayarao09.anu.brain.litertlm

import org.junit.Assert.assertEquals
import org.junit.Test

class KeptConversationsTest {
    @Test
    fun `the TPU keeps one conversation, other backends two`() {
        assertEquals(1, keptFor("NPU_TENSOR_G5"))
        assertEquals(2, keptFor("GPU"))
        assertEquals(2, keptFor("CPU"))
        assertEquals(2, keptFor(null))
    }
}
