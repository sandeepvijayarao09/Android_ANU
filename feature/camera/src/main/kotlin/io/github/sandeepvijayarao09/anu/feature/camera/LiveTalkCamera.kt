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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sandeepvijayarao09.anu.core.model.Frame
import java.util.concurrent.Executors

/**
 * The back camera during live talk (ADR-137), as Gemini Live's camera: the preview fills the live screen, and each
 * spoken question takes the camera's latest frame. [onFrames] is given a source of that frame as a JPEG while the
 * camera is open, and null once it closes. Frames stay in memory, the newest only, and are never saved (MEDIA-4).
 */
@Composable
fun LiveTalkCamera(modifier: Modifier, onFrames: ((() -> ByteArray?)?) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val analysisThread = remember { Executors.newSingleThreadExecutor { Thread(it, "anu-live-talk-camera") } }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val latest = remember { LatestFrame() }
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
                        .setResolutionStrategy(ResolutionStrategy(Size(1024, 768), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisThread) { image ->
                image.use {
                    // A few frames a second is plenty: only the newest is asked for, when the user stops talking.
                    if (latest.due()) {
                        val plane = it.planes[0]
                        latest.frame = uprightFrame(plane.buffer, it.width, it.height, plane.rowStride, it.imageInfo.rotationDegrees)
                    }
                }
            }
            runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
            }
            onFrames { latest.frame?.toJpeg(maxSide = 768) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            onFrames(null)
            latest.frame = null
            provider?.unbindAll()
            analysisThread.shutdown()
        }
    }
    AndroidView({ preview }, modifier)
}

/** The camera's newest frame, kept at most every [EVERY_MS]. */
private class LatestFrame {
    @Volatile var frame: Frame? = null
    private var at = 0L

    fun due(): Boolean {
        val now = System.currentTimeMillis()
        if (now - at < EVERY_MS) return false
        at = now
        return true
    }

    private companion object {
        const val EVERY_MS = 250L
    }
}
