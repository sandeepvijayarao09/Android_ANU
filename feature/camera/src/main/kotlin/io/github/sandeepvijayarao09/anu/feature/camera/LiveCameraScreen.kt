package io.github.sandeepvijayarao09.anu.feature.camera

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import io.github.sandeepvijayarao09.anu.core.model.Frame
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuSearchField
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.PrivacyIndicator
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Live camera (ADR-111, DESIGN.md "Live camera"): the back camera full screen, a box around each thing YOLOX finds
 * with its name, a question to find things by meaning, and "Ask Anu about this", which hands the frame to Talk.
 * Boxes are white, never green or orange, which mean where data is. Nothing here leaves the phone.
 */
@Composable
fun LiveCameraScreen(
    viewModel: LiveCameraViewModel,
    cameraAllowed: Boolean,
    onAllowCamera: () -> Unit,
    sentToday: Int,
    onBack: () -> Unit,
    /** The frozen frame and what the detector found in it, for Talk's composer. */
    onAsk: (Frozen) -> Unit,
    /** Debug builds only: photo files shown in turn instead of the camera, for emulators whose camera shows a pattern. */
    stills: List<String>? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AnuTheme.colors
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (stills != null) {
            StillFeed(viewModel, stills, Modifier.fillMaxSize())
            Boxes(state, Modifier.fillMaxSize())
        } else if (cameraAllowed) {
            CameraFeed(viewModel, Modifier.fillMaxSize())
            Boxes(state, Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp).widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Anu needs the camera to find things around you.", style = AnuTheme.type.title, color = colors.text, textAlign = TextAlign.Center)
                Spacer(Modifier.size(10.dp))
                Text(
                    "Frames are looked at on this phone and never saved.",
                    style = AnuTheme.type.callout, color = colors.textMuted, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(20.dp))
                AnuButton("Allow camera", onClick = onAllowCamera)
            }
        }
        TopBar(state, sentToday, onBack, Modifier.align(Alignment.TopCenter))
        Notice(state, Modifier.align(Alignment.Center))
        if (cameraAllowed || stills != null) {
            Panel(
                state,
                onQuestion = viewModel::ask,
                onAsk = { viewModel.freeze()?.let(onAsk) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun TopBar(state: LiveCameraState, sentToday: Int, onBack: () -> Unit, modifier: Modifier) {
    val colors = AnuTheme.colors
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.heightIn(min = 48.dp).clip(AnuShapes.full).background(SCRIM).clickable(role = Role.Button, onClickLabel = "Back to Talk", onClick = onBack)
                    .padding(start = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AnuIcons.ChevronLeft, contentDescription = null, Modifier.size(20.dp), tint = colors.text)
                Text("Talk", style = AnuTheme.type.headline, color = colors.text)
            }
            Spacer(Modifier.weight(1f))
            PrivacyIndicator(sentToday, onClick = null)
        }
        if (state.model.isNotEmpty() && state.status == CameraStatus.RUNNING) {
            // Exact values in mono (DESIGN.md): what runs where, and how fast.
            Text(
                "${state.model} · ${state.backend} · ${"%.0f".format(state.fps)} fps · ${"%.0f".format(state.detectMs)} ms",
                Modifier.padding(top = 8.dp, start = 4.dp).clip(AnuShapes.sm).background(SCRIM).padding(horizontal = 8.dp, vertical = 4.dp),
                style = AnuTheme.type.mono, color = colors.text,
            )
        }
    }
}

@Composable
private fun Notice(state: LiveCameraState, modifier: Modifier) {
    val text = when (state.status) {
        CameraStatus.PAUSED_HOT -> "Paused while the phone cools down"
        CameraStatus.FAILED -> "Anu can't look for things on this phone."
        else -> return
    }
    Text(
        text,
        modifier.padding(32.dp).clip(AnuShapes.md).background(SCRIM).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = AnuTheme.type.callout, color = AnuTheme.colors.text, textAlign = TextAlign.Center,
    )
}

@Composable
private fun Panel(state: LiveCameraState, onQuestion: (String) -> Unit, onAsk: () -> Unit, modifier: Modifier) {
    val colors = AnuTheme.colors
    Column(
        modifier.fillMaxWidth().imePadding().clip(AnuShapes.sheet).background(colors.surface)
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
    ) {
        AnuSearchField(state.question, onQuestion, placeholder = "Find something, like “anything to drink”")
        Text(
            line(state),
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = AnuTheme.type.callout, color = colors.textMuted,
        )
        AnuButton("Ask Anu about this", onClick = onAsk, modifier = Modifier.fillMaxWidth(), enabled = state.status == CameraStatus.RUNNING)
    }
}

/** What the panel says under the question. */
internal fun line(state: LiveCameraState): String {
    if (state.question.isBlank()) {
        return when {
            state.things.isEmpty() -> "Point at things around you. Anu finds them on this phone."
            else -> state.things.map { it.name }.distinct().let { names -> "In view: " + names.joinToString(", ") }
        }
    }
    if (state.thinking) return "Matching by meaning…"
    val found = state.things.filter { it.matched != null }
    return when {
        found.isNotEmpty() -> "${found.size} in view: " + found.map { it.name }.distinct().joinToString(", ")
        state.matching.isNotEmpty() -> "Looking for " + or(state.matching)
        state.byMeaning -> "Anu can spot 80 everyday things, and none of them fits that."
        else -> "No match by words. Add EmbeddingGemma 2 in Settings → Models to match by meaning."
    }
}

private fun or(names: List<String>): String = when (names.size) {
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
}

/** The boxes, placed over the preview as it fills the screen (the preview's FILL_CENTER: scaled to cover, centred). */
@Composable
private fun Boxes(state: LiveCameraState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val type = AnuTheme.type.label
    val density = LocalDensity.current
    val asking = state.question.isNotBlank() && !state.thinking
    val spoken = if (state.things.isEmpty()) "Nothing found yet" else state.things.joinToString(", ") { it.name + if (it.matched != null) ", a match" else "" }
    BoxWithConstraints(modifier.semantics { contentDescription = spoken }) {
        Canvas(Modifier.fillMaxSize()) {
            if (state.frameWidth == 0 || state.status != CameraStatus.RUNNING && state.status != CameraStatus.FROZEN) return@Canvas
            val scale = max(size.width / state.frameWidth, size.height / state.frameHeight)
            val dx = (size.width - state.frameWidth * scale) / 2
            val dy = (size.height - state.frameHeight * scale) / 2
            val corner = CornerRadius(with(density) { 6.dp.toPx() })
            for (thing in state.things) {
                val match = thing.matched != null
                val alpha = if (asking && !match) 0.3f else 1f
                val left = dx + thing.box.left * state.frameWidth * scale
                val top = dy + thing.box.top * state.frameHeight * scale
                val w = thing.box.width * state.frameWidth * scale
                val h = thing.box.height * state.frameHeight * scale
                val stroke = with(density) { (if (asking && match) 3.dp else 1.5.dp).toPx() }
                drawRoundRect(Color.White.copy(alpha = alpha * 0.9f), Offset(left, top), GeometrySize(w, h), corner, style = Stroke(stroke))
                val label = measurer.measure("${thing.name} ${(thing.score * 100).toInt()}%", type.copy(color = if (asking && match) Color.Black else Color.White.copy(alpha = alpha)))
                val padX = with(density) { 6.dp.toPx() }
                val padY = with(density) { 2.dp.toPx() }
                val chipTop = (top - label.size.height - padY * 2).coerceAtLeast(0f)
                drawRoundRect(
                    if (asking && match) Color.White else Color.Black.copy(alpha = 0.6f * alpha),
                    Offset(left, chipTop), GeometrySize(label.size.width + padX * 2, label.size.height + padY * 2), CornerRadius(corner.x * 0.7f),
                )
                drawText(label, topLeft = Offset(left + padX, chipTop + padY))
            }
        }
    }
}

/**
 * The back camera through CameraX: the preview, and frames of about 640 × 480 to the detector, the latest only, so a
 * slow frame is skipped rather than queued. Both are 4:3, so the boxes line up with the preview. The camera stops
 * when the screen leaves (its lifecycle).
 */
@Composable
private fun CameraFeed(viewModel: LiveCameraViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val analysisThread = remember { Executors.newSingleThreadExecutor { Thread(it, "anu-live-camera") } }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val cameras = future.get().also { provider = it }
            val fourByThree = AspectRatioStrategy(androidx.camera.core.AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            val shown = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourByThree).build()).build()
                .also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(fourByThree)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisThread) { image ->
                image.use {
                    val plane = it.planes[0]
                    viewModel.analyze(plane.buffer, it.width, it.height, plane.rowStride, it.imageInfo.rotationDegrees)
                }
            }
            runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            analysisThread.shutdown()
        }
    }
    AndroidView({ preview }, modifier)
}

/**
 * Debug builds' stand-in for the camera: each photo for 4 s, detected over and over as camera frames would be,
 * shown as the preview shows the camera (scaled to cover, centred), so the boxes land the same way.
 */
@Composable
private fun StillFeed(viewModel: LiveCameraViewModel, paths: List<String>, modifier: Modifier) {
    val frames = remember(paths) {
        paths.mapNotNull { path ->
            android.graphics.BitmapFactory.decodeFile(path)?.let { bitmap ->
                val scale = 640f / max(bitmap.width, bitmap.height)
                val small = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                small to Frame(small.width, small.height, IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) })
            }
        }
    }
    var shown by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(frames) {
        if (frames.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var at = android.os.SystemClock.elapsedRealtime()
            while (true) {
                kotlinx.coroutines.yield()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - at > 4_000) { shown = (shown + 1) % frames.size; at = now }
                viewModel.analyze(frames[shown].second)
            }
        }
    }
    frames.getOrNull(shown)?.let { (bitmap, _) ->
        androidx.compose.foundation.Image(
            bitmap.asImageBitmap(), contentDescription = null, modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

/** Behind text over the camera: black at 60%, so white text reads even on a white scene. */
private val SCRIM = Color.Black.copy(alpha = 0.6f)
