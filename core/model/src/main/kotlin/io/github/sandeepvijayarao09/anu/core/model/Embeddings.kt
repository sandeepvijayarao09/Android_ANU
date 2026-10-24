package io.github.sandeepvijayarao09.anu.core.model

/** What a text is embedded as: a message looking for facts, or a fact to be found (§8.3, ADR-055). */
enum class EmbeddingKind { QUERY, DOCUMENT }

/**
 * Turns text into vectors that lie close together when the meanings do (§8.3, ADR-055). It runs on the
 * phone only; the vectors are the user's data. Vectors from different [model]s are never compared.
 */
interface Embedder {
    /** The model and size the vectors come from, such as "embeddinggemma-300m@256". */
    val model: String

    /** One unit-length vector per text, in order. */
    suspend fun embed(texts: List<String>, kind: EmbeddingKind): List<FloatArray>
}

/**
 * An embedder that places pictures in the same space as words (ADR-111): EmbeddingGemma 2, so a query such as
 * "anything to drink" lands near a photo of a mug. Pictures go in as JPEG bytes and never leave the phone.
 */
interface VisualEmbedder : Embedder {
    /** One unit-length vector per picture, in order, comparable with this embedder's text vectors. */
    suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray>
}

/** How close two unit-length vectors are in meaning: their dot product, from -1 to 1. */
fun closeness(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vectors of different sizes can't be compared" }
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}
