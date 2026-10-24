package io.github.sandeepvijayarao09.anu.connector.apps

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One look at the screen for the "Ask Anu about this screen" tile (ADR-108), through app control's service: the text
 * of the apps on screen with each text's place, the password fields' places, and a screenshot. It reads only when the
 * user taps the tile, asks Android for no events to do it, and keeps nothing: the caller masks what it gets and holds
 * it in memory for one question (ADR-083).
 */
object TileScreen {
    val ready: Boolean get() = AnuAccessibilityService.instance != null

    sealed interface Look {
        /**
         * [texts] and [secrets] are in screen pixels, as [screenshot] is. A password field's own text is never read (I1).
         * [waitedMs] is how long the panel took to go, for debug builds' timing.
         */
        class Read(val packageName: String, val screenshot: Bitmap, val texts: List<Pair<String, Rect>>, val secrets: List<Rect>, val waitedMs: Long) : Look

        /** The app keeps its screen private (`FLAG_SECURE`), so Android gave no picture. */
        data object Private : Look

        /** App control is off, or the screen couldn't be read. */
        data object Unavailable : Look
    }

    /**
     * Waits for the Quick Settings panel and [ownPackage]'s own windows to leave the screen (at most 2 s), so the picture
     * shows the app, then reads it.
     */
    suspend fun read(ownPackage: String): Look {
        val service = AnuAccessibilityService.instance ?: return Look.Unavailable
        // The service keeps the windows and nodes it last saw until an event says they changed, and it asks for none
        // between tasks: on the emulator the panel's window was still listed, alone, long after it closed. So the cache
        // is cleared before each look, or, before Android 14, window changes are heard while the tile reads.
        val hearing = Build.VERSION.SDK_INT < 34 && service.hearWindows(true)
        try {
            val started = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - started < CLEAR_MAX_MS && !clear(fresh(service), ownPackage)) delay(50)
            val waited = SystemClock.uptimeMillis() - started
            // The panel's closing animation runs a little after its window goes.
            delay(SETTLE_MS)
            return look(fresh(service), waited)
        } finally {
            if (hearing) service.hearWindows(false)
        }
    }

    private fun fresh(service: AnuAccessibilityService): AnuAccessibilityService = service.also {
        if (Build.VERSION.SDK_INT >= 34) it.clearCache()
    }

    private suspend fun look(service: AccessibilityService, waited: Long): Look {
        val (packageName, texts, secrets) = withContext(Dispatchers.Default) { tree(service) } ?: return Look.Unavailable
        var shot = screenshot(service)
        if (shot is Shot.TooSoon) {
            // Android allows one screenshot a second.
            delay(1_000)
            shot = screenshot(service)
        }
        return when (shot) {
            is Shot.Taken -> Look.Read(packageName, shot.bitmap, texts, secrets, waited)
            Shot.Secure -> Look.Private
            else -> Look.Unavailable
        }
    }

    /** True once no large system window (the expanded panel) and none of [ownPackage]'s windows are showing. */
    private fun clear(service: AccessibilityService, ownPackage: String): Boolean {
        val height = service.resources.displayMetrics.heightPixels
        return service.windows.none { window ->
            val bounds = Rect().also(window::getBoundsInScreen)
            window.type == AccessibilityWindowInfo.TYPE_SYSTEM && bounds.height() > height / 2 ||
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.root?.packageName?.toString() == ownPackage
        }
    }

    /** The active app's package, and the texts and password fields of every app window on screen. */
    private fun tree(service: AccessibilityService): Triple<String, List<Pair<String, Rect>>, List<Rect>>? {
        val apps = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val active = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
        val packageName = (active?.root ?: service.rootInActiveWindow)?.packageName?.toString() ?: return null
        val texts = mutableListOf<Pair<String, Rect>>()
        val secrets = mutableListOf<Rect>()
        val roots = apps.mapNotNull { it.root }.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
        for (root in roots) walk(root, texts, secrets, IntArray(1))
        return Triple(packageName, texts, secrets)
    }

    private fun walk(node: AccessibilityNodeInfo, texts: MutableList<Pair<String, Rect>>, secrets: MutableList<Rect>, seen: IntArray) {
        if (++seen[0] > MAX_NODES) return
        val rect = Rect().also(node::getBoundsInScreen)
        if (node.isPassword) {
            secrets += rect
        } else {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it to rect }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, texts, secrets, seen) }
    }

    private sealed interface Shot {
        class Taken(val bitmap: Bitmap) : Shot
        data object Secure : Shot
        data object TooSoon : Shot
        data object Failed : Shot
    }

    private suspend fun screenshot(service: AccessibilityService): Shot = suspendCancellableCoroutine { done ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    // A software copy, so it can be masked and scaled; the hardware buffer goes at once.
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, true)
                    buffer.close()
                    done.resume(bitmap?.let(Shot::Taken) ?: Shot.Failed)
                }

                override fun onFailure(errorCode: Int) {
                    done.resume(
                        when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> Shot.Secure
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> Shot.TooSoon
                            else -> Shot.Failed
                        },
                    )
                }
            },
        )
    }

    private const val CLEAR_MAX_MS = 2_000L
    private const val SETTLE_MS = 350L
    private const val MAX_NODES = 4_000
}
