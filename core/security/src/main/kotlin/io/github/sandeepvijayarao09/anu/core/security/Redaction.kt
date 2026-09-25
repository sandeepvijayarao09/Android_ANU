package io.github.sandeepvijayarao09.anu.core.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Replaces PII with stable placeholders such as `[EMAIL_1]` across one cloud request, and restores
 * them in the response (§12.2). The mapping stays in memory on the device and is never sent.
 */
class RedactionSession {
    private val placeholderByValue = mutableMapOf<String, String>()
    private val valueByPlaceholder = mutableMapOf<String, String>()
    private val counters = mutableMapOf<PiiKind, Int>()

    /** How many distinct values of each kind were replaced. The ledger records this, not the values. */
    val counts: Map<PiiKind, Int> get() = counters.toMap()

    fun redact(text: String): String {
        val matches = PiiDetector.find(text)
        if (matches.isEmpty()) return text
        val out = StringBuilder(text.length)
        var last = 0
        for (match in matches) {
            out.append(text, last, match.range.first).append(placeholderFor(match))
            last = match.range.last + 1
        }
        return out.append(text, last, text.length).toString()
    }

    fun redact(json: JsonElement): JsonElement = json.mapStrings(::redact)

    fun redact(json: JsonObject): JsonObject = json.mapStrings(::redact)

    fun restore(text: String): String = PLACEHOLDER.replace(text) { valueByPlaceholder[it.value] ?: it.value }

    fun restore(json: JsonElement): JsonElement = json.mapStrings(::restore)

    fun restore(json: JsonObject): JsonObject = json.mapStrings(::restore)

    fun streamRestorer(): StreamRestorer = StreamRestorer(this)

    private fun placeholderFor(match: PiiMatch): String = placeholderByValue.getOrPut(match.value) {
        val n = (counters[match.kind] ?: 0) + 1
        counters[match.kind] = n
        "[${match.kind.label}_$n]".also { valueByPlaceholder[it] = match.value }
    }

    internal companion object {
        val LABELS = PiiKind.entries.map { it.label }
        val PLACEHOLDER = Regex("\\[(${LABELS.joinToString("|")})_\\d+]")
    }
}

/**
 * Restores placeholders in streamed text. A placeholder can be split across chunks, so a tail that
 * could still become one is held back until the next chunk or [flush].
 */
class StreamRestorer internal constructor(private val session: RedactionSession) {
    private val pending = StringBuilder()

    /** Returns the text that is safe to show now. */
    fun push(chunk: String): String {
        pending.append(chunk)
        val cut = holdFrom()
        val ready = pending.substring(0, cut)
        pending.delete(0, cut)
        return session.restore(ready)
    }

    fun flush(): String = session.restore(pending.toString()).also { pending.setLength(0) }

    private fun holdFrom(): Int {
        val open = pending.lastIndexOf("[")
        if (open < 0) return pending.length
        val tail = pending.substring(open)
        return if (couldBecomePlaceholder(tail)) open else pending.length
    }

    private fun couldBecomePlaceholder(tail: String): Boolean = RedactionSession.LABELS.any { label ->
        val prefix = "[${label}_"
        if (tail.length <= prefix.length) prefix.startsWith(tail) else tail.startsWith(prefix) && tail.drop(prefix.length).all(Char::isDigit)
    }
}

private fun JsonObject.mapStrings(transform: (String) -> String): JsonObject =
    JsonObject(mapValues { it.value.mapStrings(transform) })

private fun JsonElement.mapStrings(transform: (String) -> String): JsonElement = when (this) {
    is JsonObject -> mapStrings(transform)
    is JsonArray -> JsonArray(map { it.mapStrings(transform) })
    is JsonPrimitive -> if (isString) JsonPrimitive(transform(content)) else this
}
