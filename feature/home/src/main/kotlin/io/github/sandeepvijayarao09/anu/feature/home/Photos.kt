package io.github.sandeepvijayarao09.anu.feature.home

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.model.Photo
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Photos as Anu keeps them (ADR-075): a JPEG at most this long on its longer side, which also bounds the model's work. */
internal const val PHOTO_MAX_SIDE = 1_024

/**
 * Reads a picked photo and makes it Anu's size: at most [PHOTO_MAX_SIDE] pixels on its longer side, as a JPEG. Null
 * when it can't be read. The original is never kept, and nothing about it leaves the phone (MEDIA-1).
 */
fun readPhoto(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val longer = max(info.size.width, info.size.height)
        if (longer > PHOTO_MAX_SIDE) {
            val scale = PHOTO_MAX_SIDE.toFloat() / longer
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        // Software, so the bitmap can be compressed; hardware bitmaps can't be read back.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}.getOrNull()

/**
 * A photo in the thread (design review 3A): with its bytes when the screen has them, else loaded from Anu's store.
 * One that's no longer kept keeps its place, with a glyph and a quiet line (design review D19).
 */
@Composable
internal fun PhotoView(photo: Photo, load: suspend (Long) -> ByteArray?, maxWidth: Dp = 240.dp) {
    val bytes by produceState(initialValue = photo.bytes, photo) { if (value == null && photo.id != 0L) value = load(photo.id) }
    val image: ImageBitmap? = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (image != null) {
        Image(
            image,
            contentDescription = "Photo you shared",
            modifier = Modifier.widthIn(max = maxWidth).heightIn(max = 320.dp).clip(AnuShapes.md),
            contentScale = ContentScale.Fit,
        )
    } else if (bytes == null && photo.id != 0L) {
        NoLongerKept()
    }
}

@Composable
private fun NoLongerKept() {
    val colors = AnuTheme.colors
    Row(
        Modifier.clip(AnuShapes.md).background(colors.surface2).padding(horizontal = 12.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = "Photo you shared, no longer kept" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AnuIcons.Photo, contentDescription = null, Modifier.size(18.dp), tint = colors.textMuted)
        Text("Photo no longer kept", style = AnuTheme.type.caption, color = colors.textMuted)
    }
}
