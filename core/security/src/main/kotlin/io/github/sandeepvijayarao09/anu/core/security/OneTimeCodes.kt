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

    fun mask(text: String): String = if (looksLikeVerification(text)) DIGITS.replace(text, MASK) else text
}
