package io.github.sandeepvijayarao09.anu.core.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Small vector helpers for search by meaning (ADR-055). Vectors are stored and compared at unit length. */
object Vectors {
    /** Cosine similarity: the dot product of unit vectors. Vectors of different sizes don't compare. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * The first [dims] values at unit length. Matryoshka-trained models such as EmbeddingGemma keep most of
     * their quality this way, and the vectors take a third of the space.
     */
    fun truncate(vector: FloatArray, dims: Int): FloatArray = normalize(if (vector.size > dims) vector.copyOf(dims) else vector)

    fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.fold(0f) { sum, v -> sum + v * v })
        return if (norm == 0f) vector else FloatArray(vector.size) { vector[it] / norm }
    }

    /** Little-endian floats, for the encrypted database. */
    fun toBytes(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach(::putFloat) }.array()

    fun fromBytes(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
