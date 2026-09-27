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
