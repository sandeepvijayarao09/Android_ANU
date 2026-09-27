package io.github.sandeepvijayarao09.anu.core.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorsTest {
    @Test
    fun `cosine is 1 for the same direction, 0 for unrelated, and 0 for a size mismatch`() {
        assertEquals(1f, Vectors.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f)), 0f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0f)
    }

    @Test
    fun `a cut vector keeps its first values at unit length`() {
        val cut = Vectors.truncate(floatArrayOf(3f, 4f, 12f), 2)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), cut, 1e-6f)
    }

    @Test
    fun `vectors survive the trip through the database`() {
        val vector = floatArrayOf(0.25f, -0.5f, 1e-7f, 3.5f)
        assertArrayEquals(vector, Vectors.fromBytes(Vectors.toBytes(vector)), 0f)
    }
}
