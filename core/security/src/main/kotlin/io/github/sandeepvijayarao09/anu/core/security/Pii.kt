package io.github.sandeepvijayarao09.anu.core.security

import io.github.sandeepvijayarao09.anu.core.model.DataClass

/** What the redactor replaces before cloud egress (§12.2). Names are not detected in v1. */
enum class PiiKind(val label: String, val dataClass: DataClass) {
    EMAIL("EMAIL", DataClass.PERSONAL),
    PHONE("PHONE", DataClass.PERSONAL),
    CARD("CARD", DataClass.SENSITIVE),
    IBAN("IBAN", DataClass.SENSITIVE),
    NATIONAL_ID("ID", DataClass.SENSITIVE),
}

data class PiiMatch(val kind: PiiKind, val range: IntRange, val value: String)

/**
 * Finds contact details and financial or ID numbers in text. Overlapping candidates resolve to the
 * earliest match, then the longest, so a value is never split into mangled pieces (§18).
 */
object PiiDetector {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val NATIONAL_ID = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b")
    private val CARD = Regex("\\b(?:\\d[ -]?){12,18}\\d\\b")
    private val PHONE = Regex("(?<![\\w+])\\+?\\(?\\d[\\d ().-]{7,}\\d(?!\\w)")
    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}")

    fun find(text: String): List<PiiMatch> {
        // Insertion order breaks ties between same-length matches: an exact card beats a phone-shaped run.
        val candidates = buildList {
            EMAIL.findAll(text).forEach { add(PiiMatch(PiiKind.EMAIL, it.range, it.value)) }
            NATIONAL_ID.findAll(text).forEach { add(PiiMatch(PiiKind.NATIONAL_ID, it.range, it.value)) }
            IBAN.findAll(text).forEach { add(PiiMatch(PiiKind.IBAN, it.range, it.value)) }
            CARD.findAll(text).filter { passesLuhn(it.value) }.forEach { add(PiiMatch(PiiKind.CARD, it.range, it.value)) }
            PHONE.findAll(text).filter { looksLikePhone(it.value) }.forEach { add(PiiMatch(PiiKind.PHONE, it.range, it.value)) }
        }
        val ordered = candidates.sortedWith(
            compareBy<PiiMatch> { it.range.first }.thenByDescending { it.range.last - it.range.first },
        )
        val kept = mutableListOf<PiiMatch>()
        var end = -1
        for (match in ordered) {
            if (match.range.first > end) {
                kept += match
                end = match.range.last
            }
        }
        return kept
    }

    fun dataClasses(text: String): Set<DataClass> = find(text).mapTo(mutableSetOf()) { it.kind.dataClass }

    private fun digits(value: String) = value.filter(Char::isDigit)

    private fun looksLikePhone(value: String): Boolean = digits(value).length in 10..15 && !ISO_DATE.containsMatchIn(value)

    private fun passesLuhn(value: String): Boolean {
        val number = digits(value)
        if (number.length !in 13..19) return false
        val sum = number.reversed().mapIndexed { i, c ->
            val d = c - '0'
            if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum()
        return sum % 10 == 0
    }
}
