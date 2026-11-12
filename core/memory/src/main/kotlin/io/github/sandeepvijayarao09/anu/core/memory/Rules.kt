package io.github.sandeepvijayarao09.anu.core.memory

import io.github.sandeepvijayarao09.anu.core.security.OneTimeCodes
import io.github.sandeepvijayarao09.anu.core.security.PiiDetector
import io.github.sandeepvijayarao09.anu.core.security.PiiKind
import java.security.MessageDigest

/** What memory may keep (I1, §12.1). These rules run before anything is stored. */
internal object MemoryFilters {
    /** Messages about credentials are never kept or learned from, even on the phone. */
    private val CREDENTIAL = Regex(
        "\\b(passwords?|passcodes?|passphrases?|pin (code|number|is)|api keys?|secret keys?|access tokens?|" +
            "one[- ]time (code|password)|verification code|security code|otp)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Card, bank and ID numbers are never kept in memory, so they're cut from what is stored. */
    private val NEVER_KEPT = setOf(PiiKind.CARD, PiiKind.IBAN, PiiKind.NATIONAL_ID)

    /**
     * Learning is limited to messages where the user talks about themself or people in their life, such
     * as "my manager…" or "I'm vegetarian". Requests like "Book lunch with Maya" hold no lasting fact,
     * and skipping them saves a model call each.
     */
    private val ABOUT_SOMEONE = Regex("\\b(i|i'm|im|i've|i'll|i'd|my|me|mine|myself|we|we're|our|us)\\b", RegexOption.IGNORE_CASE)

    /** The text to store for a chat message, or null if it shouldn't be kept or learned from. */
    fun forChat(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS || !ABOUT_SOMEONE.containsMatchIn(trimmed)) return null
        return cleaned(trimmed)
    }

    /** The text to store for a statement the user made directly, or null if it holds a secret. */
    fun cleaned(text: String): String? {
        if (CREDENTIAL.containsMatchIn(text) || OneTimeCodes.looksLikeVerification(text)) return null
        var out = text
        PiiDetector.find(out).filter { it.kind in NEVER_KEPT }.sortedByDescending { it.range.first }.forEach { match ->
            out = out.replaceRange(match.range, "[${match.kind.label}]")
        }
        return out
    }

    /**
     * A fact may be kept only if it carries no credential, code, or card, bank or ID number. Underscores
     * read as spaces, so a relation such as `wifi_password` is caught too.
     */
    fun safeToKeep(text: String): Boolean {
        val plain = text.replace('_', ' ')
        return !CREDENTIAL.containsMatchIn(plain) &&
            !OneTimeCodes.looksLikeVerification(plain) &&
            PiiDetector.find(plain).none { it.kind in NEVER_KEPT }
    }

    private const val MIN_CHARS = 8
}

/** How subjects, relations and values are compared, so "I", "me" and "user" are the same person. */
internal object Normalize {
    private val SELF = setOf("i", "me", "my", "mine", "myself", "user", "the user", "you", "yourself", "the user's")

    fun subject(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.lowercase() in SELF) USER else trimmed
    }

    fun predicate(raw: String): String =
        raw.trim().lowercase().replace(Regex("[\\s-]+"), "_").replace(Regex("[^a-z0-9_]"), "").replace(Regex("_+"), "_").trim('_')
            .let { CANONICAL[it] ?: it }

    /**
     * One word for each relation (ADR-144). On the Pixel, the memory eval found E4B writing "residence", "location",
     * "workplace" and "hobby" where the extractor's examples say lives_in, works_at and likes: a move then left two
     * homes, and a question about where someone lives missed. Every fact is stored and compared under its canonical
     * word, so a one-at-a-time relation is replaced whatever word the model chose.
     */
    private val CANONICAL: Map<String, String> = buildMap {
        fun to(canonical: String, vararg words: String) = words.forEach { put(it, canonical) }
        to("lives_in", "residence", "resides_in", "location", "current_location", "current_city", "city", "home_city", "home", "lives", "lives_at", "based_in", "located_in")
        to("works_at", "workplace", "employer", "company", "works_for", "employed_at", "employed_by", "job_at", "organization", "workplace_name")
        to("job_title", "job", "occupation", "profession", "role", "position", "title")
        to("likes", "hobby", "hobbies", "enjoys", "interest", "interests", "likes_to", "loves", "passion")
        to("dislikes", "hates", "doesnt_like")
        to("birthday", "birth_date", "date_of_birth", "born_on", "dob")
        to("diet", "dietary_preference", "dietary_restriction")
        to("partner", "spouse", "wife", "husband", "girlfriend", "boyfriend", "significant_other")
        to("allergic_to", "allergy", "allergies", "allergic")
        to("manager", "boss", "supervisor")
    }

    fun value(raw: String): String = words(raw).joinToString(" ")

    fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty)

    /** A deleted fact is remembered only as this hash, never as text (§8.4). */
    fun fingerprint(subject: String, predicate: String, value: String): String {
        val key = "${subject.lowercase()}|$predicate|${value(value)}"
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Relations a person has one of at a time. A new value replaces the old one (§8.2, step 3). */
    val SINGLE_VALUED = setOf("name", "age", "birthday", "pronouns", "works_at", "job_title", "manager", "lives_in", "hometown", "timezone", "diet", "partner")
}
