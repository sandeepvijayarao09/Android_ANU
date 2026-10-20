package io.github.sandeepvijayarao09.anu.feature.home

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.core.content.FileProvider
import io.github.sandeepvijayarao09.anu.core.model.Photo
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import java.io.ByteArrayOutputStream
import java.io.File
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
 * Where the system camera writes a photo for Anu (ADR-094): one file in Anu's cache, which only the camera app is let
 * write, through Anu's own file provider, for this capture. [take] reads it as a picked photo is read, to Anu's size,
 * and deletes it, so the full-size original is never kept.
 */
internal class CameraShot(private val context: Context) {
    private val file = File(context.cacheDir, "camera/shot.jpg")

    /** The address the camera app writes to. Any photo left from before is cleared first. */
    fun target(): Uri {
        file.parentFile?.mkdirs()
        file.delete()
        return FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
    }

    /** The photo, if one was [taken] and can be read. The file is deleted either way. */
    fun take(taken: Boolean): ByteArray? = try {
        if (taken && file.length() > 0) readPhoto(context.contentResolver, Uri.fromFile(file)) else null
    } finally {
        file.delete()
    }
}

/**
 * A message's photos in your turn (ADR-094): one as it is; several as a grid of square crops, two to a row, as wide as
 * one photo. Photos let go together, with their message (ADR-091), so once none is kept a single line says so.
 */
@Composable
internal fun PhotosView(photos: List<Photo>, load: suspend (Long) -> ByteArray?) {
    if (photos.size <= 1) return photos.forEach { PhotoView(it, load) }
    val loaded by produceState<List<ByteArray?>?>(initialValue = null, photos) {
        value = photos.map { photo -> photo.bytes ?: if (photo.id != 0L) load(photo.id) else null }
    }
    val bytes = loaded ?: return
    if (bytes.all { it == null }) return NoLongerKept(photos.size)
    Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        bytes.filterNotNull().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) { row.forEach { Thumbnail(it, GRID_CELL, "Photo you shared") } }
        }
    }
}

private val GRID_GAP = 4.dp
private val GRID_CELL = 118.dp

/** A photo cropped to a square [side], or nothing when its bytes can't be read. */
@Composable
internal fun Thumbnail(jpeg: ByteArray, side: Dp, description: String?) {
    val image = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() } ?: return
    Image(image, contentDescription = description, modifier = Modifier.size(side).clip(AnuShapes.md), contentScale = ContentScale.Crop)
}

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
private fun NoLongerKept(count: Int = 1) {
    val colors = AnuTheme.colors
    val line = if (count == 1) "Photo no longer kept" else "$count photos no longer kept"
    Row(
        Modifier.clip(AnuShapes.md).background(colors.surface2).padding(horizontal = 12.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = if (count == 1) "Photo you shared, no longer kept" else "$count photos you shared, no longer kept" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AnuIcons.Photo, contentDescription = null, Modifier.size(18.dp), tint = colors.textMuted)
        Text(line, style = AnuTheme.type.caption, color = colors.textMuted)
    }
}
