package io.github.sandeepvijayarao09.anu.core.agent

/**
 * Puts back a long code a tool returned when the answer copies it with a slip (ADR-136). On the Pixel, E2B retyped
 * calculate-hash's 64-character SHA-256 with two wrong characters. A code here is a run of 16 or more letters, digits
 * or `+/=_-` holding a digit: a hash, an ID or a token. An answer's code that no tool returned, but that starts like
 * one that a tool did and is about as long, becomes that one.
 */
object ExactCopies {
    private val CODE = Regex("[A-Za-z0-9+/=_-]{16,}")

    fun fix(answer: String, returned: List<String>): String {
        val codes = returned.flatMap { text -> CODE.findAll(text).map { it.value }.filter(::hasDigit).toList() }.distinct()
        if (codes.isEmpty()) return answer
        return CODE.replace(answer) { match ->
            val said = match.value
            if (!hasDigit(said) || said in codes) return@replace said
            codes.filter { abs(it.length - said.length) <= MAX_LENGTH_DIFF && it.commonPrefixWith(said).length >= SAME_START }
                .maxByOrNull { it.commonPrefixWith(said).length } ?: said
        }
    }

    private fun hasDigit(code: String) = code.any(Char::isDigit)

    private fun abs(n: Int) = if (n < 0) -n else n

    /** How alike the start must be: enough that an unrelated code never matches. */
    private const val SAME_START = 8

    /** A slip drops or doubles a few characters. */
    private const val MAX_LENGTH_DIFF = 4
}
