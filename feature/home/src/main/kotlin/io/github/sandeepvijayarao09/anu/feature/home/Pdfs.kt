package io.github.sandeepvijayarao09.anu.feature.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import io.github.sandeepvijayarao09.anu.core.model.Documents
import io.github.sandeepvijayarao09.anu.core.model.Pdf
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** What reading a picked or shared PDF gave (ADR-095). */
sealed interface PdfRead {
    data class Ready(val pdf: Pdf) : PdfRead

    /** It's locked with a password, which Anu never asks for. */
    data object Locked : PdfRead

    data object Unreadable : PdfRead
}

/**
 * Reads a PDF on the phone with Android's own renderer (ADR-095): the text of its first [Documents.MAX_PAGES] pages
 * where Android can give it (15 and later), else pictures of up to [pictures] pages, drawn as photos are kept, at most
 * [PHOTO_MAX_SIDE] pixels. Nothing is kept: the result stays in memory until its message is sent, and nothing about the
 * PDF leaves the phone (MEDIA-1).
 */
fun readPdf(context: Context, uri: Uri, pictures: Int): PdfRead = try {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "Document.pdf"
    // Not being let read the file is "can't be read"; only the renderer's refusal means a password (SecurityException).
    val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return PdfRead.Unreadable
    val opened = try {
        PdfRenderer(descriptor)
    } catch (_: SecurityException) {
        descriptor.close()
        return PdfRead.Locked
    }
    opened.use { renderer ->
        val pages = renderer.pageCount
        val reading = minOf(pages, Documents.MAX_PAGES)
        val texts = if (Build.VERSION.SDK_INT >= 35) (0 until reading).map { index -> renderer.text(index) } else emptyList()
        val drawn = if (texts.any { it.isNotBlank() } || pictures <= 0) {
            emptyList()
        } else {
            (0 until minOf(pages, pictures)).map { index -> renderer.picture(index) }
        }
        if (pages == 0) PdfRead.Unreadable else PdfRead.Ready(Pdf(name, pages, texts, drawn))
    }
} catch (_: Exception) {
    PdfRead.Unreadable
}

private fun PdfRenderer.text(index: Int): String =
    if (Build.VERSION.SDK_INT >= 35) openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } } else ""

/** Page [index] on white, as a JPEG at most [PHOTO_MAX_SIDE] on its longer side. */
private fun PdfRenderer.picture(index: Int): ByteArray = openPage(index).use { page ->
    val scale = PHOTO_MAX_SIDE.toFloat() / max(page.width, page.height)
    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray().also { bitmap.recycle() }
}

/** Why a PDF couldn't be added. */
const val PDF_LOCKED = "This PDF is locked with a password, so Anu can't read it."
const val PDF_UNREADABLE = "This PDF can't be read."

/** "Lease.pdf · 12 pages". */
internal fun pdfLabel(name: String, pages: Int): String = "$name · " + if (pages == 1) "1 page" else "$pages pages"

/**
 * A PDF as a `full` pill (DESIGN.md), the voice note's look: in the composer with Remove beside it, and in your turn
 * above your words. TalkBack reads it as one item.
 */
@Composable
internal fun PdfPill(name: String, pages: Int, modifier: Modifier = Modifier) {
    val colors = AnuTheme.colors
    Row(
        modifier.widthIn(max = 260.dp).clip(AnuShapes.full).background(colors.surface2).padding(horizontal = 12.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "PDF, ${pdfLabel(name, pages)}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AnuIcons.Note, contentDescription = null, Modifier.size(16.dp), tint = colors.textMuted)
        Text(pdfLabel(name, pages), style = AnuTheme.type.callout, color = colors.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}
