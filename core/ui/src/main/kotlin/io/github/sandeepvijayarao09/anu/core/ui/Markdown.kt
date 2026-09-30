package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The small Markdown subset models actually write in chat: **bold**, *italic* or _italic_, `code`,
 * `#` headings, and `-`/`*` bullets, for answers and their reasoning, in the chat and the prompt lab (ADR-090).
 * Links are shown as plain text on purpose. A chat bubble never becomes a tap target for a URL a model
 * produced (P4).
 */
fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(heading.groupValues[1]) }
            bullet != null -> {
                append(bullet.groupValues[1])
                append("• ")
                inline(bullet.groupValues[2])
            }
            else -> inline(line)
        }
    }
}

private val HEADING = Regex("#{1,6}\\s+(.*)")
private val BULLET = Regex("(\\s*)[-*]\\s+(.*)")
// Single * and _ only count at word edges, so identifiers like calendar_create_event stay intact.
private val INLINE = Regex(
    "\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![\\w*])\\*([^*\\s][^*]*?)\\*(?![\\w*])|(?<![\\w])_([^_\\s][^_]*?)_(?![\\w])",
)

private fun AnnotatedString.Builder.inline(text: String) {
    var last = 0
    for (match in INLINE.findAll(text)) {
        append(text, last, match.range.first)
        val (bold, code, star, underscore) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            star.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(star) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(underscore) }
        }
        last = match.range.last + 1
    }
    append(text, last, text.length)
}
