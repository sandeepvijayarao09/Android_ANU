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

    /**
     * [answer] with its secrets masked. [asked] is what the user wrote, which can make it an answer about a code.
     * [known] are codes found on the screen the answer is about (ADR-083): masked wherever they appear, however the
     * answer puts them, since a code can come back without the word "code" ("a file named 482913.txt").
     */
    fun mask(answer: String, asked: String, known: Collection<String> = emptyList()): String {
        var out = answer
        for (code in known) {
            val digits = code.filter(Char::isDigit)
            if (digits.length < 4) continue
            // "482913", "482 913" and "482-913" are the same code.
            out = Regex(digits.toList().joinToString("[ -]?") { Regex.escape(it.toString()) }).replace(out, OneTimeCodes.MASK)
        }
        return LABELLED.replace(OneTimeCodes.mask(out, asked)) { match -> match.groupValues[1] + match.groupValues[2] + OneTimeCodes.MASK }
    }
}
