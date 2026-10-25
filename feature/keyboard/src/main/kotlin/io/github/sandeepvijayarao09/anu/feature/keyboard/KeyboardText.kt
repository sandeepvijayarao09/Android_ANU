package io.github.sandeepvijayarao09.anu.feature.keyboard

/**
 * What the field held around the cursor when the user tapped an AI action. The keyboard reads it once, at that
 * tap, through `InputConnection`, and never while the user types (ADR-109).
 */
data class FieldText(val before: String, val selected: String = "")

enum class EditMode {
    /** The result replaces the selection. */
    REPLACE_SELECTION,

    /** The result replaces [EditPlan.source], the text just before the cursor. */
    REPLACE_BEFORE,

    /** The result goes in at the cursor, after [EditPlan.source]. */
    INSERT,
}

/**
 * Where a result goes. [source] is the text it replaces or follows, which must still be there when the user taps to
 * insert it; [input] is what the model sees. [keep]: the selection stays and the result follows it (Continue).
 */
data class EditPlan(val mode: EditMode, val source: String, val input: String, val keep: Boolean = false)

/** The pure parts of reading a field and putting a result back (ADR-109). */
object KeyboardText {
    /** How much text before the cursor is read: about a page, cut at a sentence so the model never starts mid-word. */
    const val READ_CHARS = 2_000

    private val PUNCTUATION_FIRST = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '…', '\'', '’')

    /** The part of [before] that is offered: all of it, or when it filled the read, from its first sentence break. */
    fun tail(before: String): String {
        if (before.length < READ_CHARS) return before
        val text = before.takeLast(READ_CHARS)
        val limit = minOf(text.length, 400)
        val cut = (0 until limit).firstOrNull { i ->
            text[i] == '\n' || (text[i] in ".!?" && i + 1 < text.length && text[i + 1].isWhitespace())
        }
        return if (cut == null) text else text.substring(cut + 1)
    }

    /** Improve, Shorter and the rest: the selection, or else the text before the cursor. Null when there's nothing. */
    fun forRewrite(field: FieldText): EditPlan? = when {
        field.selected.isNotBlank() -> EditPlan(EditMode.REPLACE_SELECTION, field.selected, field.selected.trim())
        else -> tail(field.before).takeIf { it.isNotBlank() }?.let { EditPlan(EditMode.REPLACE_BEFORE, it, it.trim()) }
    }

    /** Continue: the next sentence after the text before the cursor, or after the selection, which it keeps. */
    fun forContinue(field: FieldText): EditPlan? = when {
        field.selected.isNotBlank() -> EditPlan(EditMode.REPLACE_SELECTION, field.selected, field.selected.trim(), keep = true)
        else -> tail(field.before).takeIf { it.isNotBlank() }?.let { EditPlan(EditMode.INSERT, it, it.trim()) }
    }

    /** Reply: what the user started becomes the reply, which replaces it; an empty field gets the reply at the cursor. */
    fun forReply(field: FieldText): EditPlan = forRewrite(field) ?: EditPlan(EditMode.INSERT, "", "")

    /** The text to commit for [result]: a replacement keeps the source's surrounding spaces, an addition gets a space. */
    fun output(plan: EditPlan, result: String): String = when {
        plan.keep -> plan.source + join(plan.source, result)
        plan.mode == EditMode.INSERT -> join(plan.source, result)
        else -> leading(plan.source) + result + trailing(plan.source)
    }

    /** [addition] as it follows [before]: with a space between words, none before punctuation or after a space. */
    fun join(before: String, addition: String): String = when {
        before.isEmpty() || addition.isEmpty() -> addition
        before.last().isWhitespace() || addition.first().isWhitespace() || addition.first() in PUNCTUATION_FIRST -> addition
        else -> " $addition"
    }

    /** Whether [plan] still fits the field as it is now. If the user typed or moved meanwhile, nothing is replaced. */
    fun stillThere(plan: EditPlan, now: FieldText): Boolean = when (plan.mode) {
        EditMode.REPLACE_SELECTION -> now.selected == plan.source
        EditMode.REPLACE_BEFORE -> now.selected.isEmpty() && now.before.endsWith(plan.source)
        EditMode.INSERT -> now.selected.isEmpty() && now.before.endsWith(plan.source)
    }

    /** A continuation that repeats the text it follows loses the repeat. */
    fun withoutEcho(input: String, result: String): String {
        val text = result.trim()
        return if (input.isNotEmpty() && text.startsWith(input)) text.removePrefix(input).trim() else text
    }

    /** Ask: with text in the field it is changed as asked; an empty field gets new text at the cursor. */
    fun forAsk(field: FieldText): EditPlan = forReply(field)

    /** Emoji go after the text before the cursor, or after the selection, which stays. */
    fun forEmoji(field: FieldText): EditPlan? = forContinue(field)

    /** The emoji in what the model wrote, each whole (skin tones, flags, joined families), without repeats: at most [max]. */
    fun emoji(raw: String, max: Int): List<String> =
        raw.split(Regex("[\\s,;|]+")).map { it.trim('.', '"', '\'') }.filter(::isEmoji).distinct().take(max)

    /** A token of emoji only: no letters or digits, and at least one symbol from the emoji ranges. */
    internal fun isEmoji(token: String): Boolean {
        if (token.isEmpty() || token.any { it.isLetterOrDigit() }) return false
        var i = 0
        var symbol = false
        while (i < token.length) {
            val cp = token.codePointAt(i)
            when {
                cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF -> Unit
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF -> symbol = true
                else -> return false
            }
            i += Character.charCount(cp)
        }
        return symbol
    }

    /** The Ask field's text after one key: typed text, delete (one character, never half of one), and a cap. */
    fun askEdit(buffer: String, typed: String?, max: Int = ASK_CHARS): String = when {
        typed != null -> (buffer + typed).take(max)
        buffer.isEmpty() -> buffer
        else -> buffer.substring(0, buffer.offsetByCodePoints(buffer.length, -1))
    }

    /** The longest instruction the Ask field takes. */
    const val ASK_CHARS = 300

    private fun leading(text: String) = text.takeWhile(Char::isWhitespace)

    private fun trailing(text: String) = text.takeLastWhile(Char::isWhitespace)
}
