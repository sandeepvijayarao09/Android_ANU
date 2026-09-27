package io.github.sandeepvijayarao09.anu.core.memory

import io.github.sandeepvijayarao09.anu.core.model.Embedder
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind

/**
 * A stand-in for EmbeddingGemma in tests: each dimension is a topic, so texts about the same thing lie
 * close together though they share no word ("plan dinner" and "My diet: vegetarian").
 */
class ConceptEmbedder(override val model: String = "concepts@4", var failing: Boolean = false) : Embedder {
    private val concepts = listOf(
        setOf("dinner", "lunch", "eat", "restaurant", "vegetarian", "diet", "meal", "recipe", "food"),
        setOf("sister", "brother", "mom", "dad", "family", "priya"),
        setOf("manager", "meeting", "boss", "office", "report", "work"),
        setOf("flight", "trip", "travel", "hotel", "pune"),
    )
    val calls = mutableListOf<Pair<List<String>, EmbeddingKind>>()

    override suspend fun embed(texts: List<String>, kind: EmbeddingKind): List<FloatArray> {
        check(!failing) { "embedding failed" }
        calls += texts to kind
        return texts.map { text ->
            val words = text.lowercase().split(Regex("[^a-z]+")).toSet()
            Vectors.normalize(FloatArray(concepts.size) { i -> concepts[i].count { it in words }.toFloat() })
        }
    }
}
