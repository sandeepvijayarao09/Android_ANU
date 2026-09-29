package io.github.sandeepvijayarao09.anu.core.security

/**
 * Secrets a picture can show, kept out of what Anu says about it (I1, ADR-082). A photo the user attaches may show the
 * on-device model a one-time code or a password. Answers about it mask them before they're shown or saved:
 * - one-time codes, as in messages ([OneTimeCodes]), when the answer or the user's words speak of a code;
 * - the value after "password", "passcode", "PIN" or "pwd".
 * Over-masking an answer is fine; repeating a secret is not.
 */
object ShownSecrets {
    private val LABELLED = Regex("\\b(password|passcode|pin|pwd)\\b(\\s*(?:is|:|=)\\s*)(\\S+?)(?=[.,;!?]?(?:\\s|$))", RegexOption.IGNORE_CASE)

    /** [answer] with its secrets masked. [asked] is what the user wrote, which can make it an answer about a code. */
    fun mask(answer: String, asked: String): String =
        LABELLED.replace(OneTimeCodes.mask(answer, asked)) { match -> match.groupValues[1] + match.groupValues[2] + OneTimeCodes.MASK }
}
