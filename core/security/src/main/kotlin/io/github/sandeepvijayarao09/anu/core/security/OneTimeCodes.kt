package io.github.sandeepvijayarao09.anu.core.security

/**
 * One-time verification codes act as credentials (S3). Tools reading messages or SMS mask them before
 * any model sees them (ADR-032, I1).
 *
 * A message is treated as a verification message when it mentions a code, OTP, PIN or sign-in. Then
 * every run of 4–8 digits, or "123 456" / "123-456", is masked. Over-masking such a message is fine;
 * leaking a code is not.
 */
object OneTimeCodes {
    const val MASK = "••••••"

    private val VERIFICATION = Regex(
        "\\b(code|otp|one[- ]time|verification|verify|passcode|pin|2fa|two[- ]factor|security code|log ?in|sign[- ]?in|authenticat\\w*)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DIGITS = Regex("(?<!\\d)(\\d{3}[ -]\\d{3}|\\d{4,8})(?!\\d)")

    fun looksLikeVerification(text: String): Boolean = VERIFICATION.containsMatchIn(text) && DIGITS.containsMatchIn(text)

    /** Whether [text] holds something shaped like a code, a run of 4 to 8 digits or "123 456". */
    fun containsCode(text: String): Boolean = DIGITS.containsMatchIn(text)

    /** The code-shaped runs in [text], as they're written. */
    fun codes(text: String): List<String> = DIGITS.findAll(text).map { it.value }.toList()

    fun mask(text: String): String = if (looksLikeVerification(text)) DIGITS.replace(text, MASK) else text

    /**
     * Like [mask], also counting [context] as part of the message: an answer can give a code without saying "code",
     * when the question asked for one ("What's the code?" "It's 482913.").
     */
    fun mask(text: String, context: String): String =
        if (VERIFICATION.containsMatchIn("$context\n$text") && DIGITS.containsMatchIn(text)) DIGITS.replace(text, MASK) else text
}
