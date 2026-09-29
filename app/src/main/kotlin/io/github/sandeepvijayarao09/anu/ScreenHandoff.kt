package io.github.sandeepvijayarao09.anu

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.util.Log
import androidx.core.graphics.scale
import io.github.sandeepvijayarao09.anu.core.model.Screen
import io.github.sandeepvijayarao09.anu.core.security.OneTimeCodes
import io.github.sandeepvijayarao09.anu.feature.home.ScreenLook
import io.github.sandeepvijayarao09.anu.feature.home.ScreenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The screen under the assistant card, as Android handed it to the session (EXP-2, ADR-083): its text structure and
 * its screenshot, held in memory only, never in an Intent or a file. The next summon replaces it, and the card drops
 * it when it closes. It's read only when the user asks about the screen.
 */
object ScreenHandoff : ScreenSource {
    private class Parts(val atMillis: Long) {
        @Volatile var structure: AssistStructure? = null
        @Volatile var structureDone = false
        @Volatile var screenshot: Bitmap? = null
        @Volatile var screenshotDone = false
    }

    @Volatile private var parts: Parts? = null

    /** How long a question waits for parts still arriving, since Android hands them over just after the summon. */
    private const val WAIT_MS = 2_000L

    /** Longest side of the picture the model gets, as for photos (ADR-075). */
    private const val MAX_SIDE = 1_024

    private lateinit var appContext: Context

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun begin(atMillis: Long) {
        parts = Parts(atMillis)
    }

    fun structure(value: AssistStructure?) {
        parts?.apply {
            structure = value
            structureDone = true
        }
    }

    fun screenshot(value: Bitmap?) {
        parts?.apply {
            screenshot = value
            screenshotDone = true
        }
    }

    /** Whether a summon handed over anything, for the card's "Use this screen". */
    override fun offered(): Boolean = parts != null

    fun clear() {
        parts = null
    }

    override suspend fun screen(): ScreenLook {
        val held = parts ?: return ScreenLook.Unavailable(CANT_SEE)
        withTimeoutOrNull(WAIT_MS) { while (!(held.structureDone && held.screenshotDone)) delay(50) }
        val screenshot = held.screenshot ?: return ScreenLook.Unavailable(CANT_SEE)
        // Without the screen's text, Anu can't find codes on it to mask, so it won't read it (SECRETS-1, ADR-082).
        val structure = held.structure ?: return ScreenLook.Unavailable(CANT_CHECK)
        val app = structure.activityComponent?.packageName?.let { name ->
            runCatching { appContext.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(name, 0)).toString() } }.getOrNull()
        } ?: "this app"
        val jpeg = withContext(Dispatchers.Default) { jpeg(masked(structure, screenshot)) }
        return ScreenLook.Ready(Screen(app, held.atMillis, jpeg))
    }

    /**
     * [screenshot] with its secrets blacked out, found from the screen's own text: every text that holds a code when the
     * screen speaks of one (as `OneTimeCodes` masks messages), and every password field.
     */
    internal fun masked(structure: AssistStructure, screenshot: Bitmap): Bitmap {
        val texts = mutableListOf<Pair<String, Rect>>()
        val secrets = mutableListOf<Rect>()
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i)
            walk(window.rootViewNode, window.left, window.top, texts, secrets)
        }
        val verification = OneTimeCodes.looksLikeVerification(texts.joinToString("\n") { it.first })
        val hidden = secrets + if (verification) texts.filter { OneTimeCodes.containsCode(it.first) }.map { it.second } else emptyList()
        // Counts only, never what the screen says.
        if (BuildConfig.DEBUG) Log.d("Anu", "Screen: ${texts.size} texts, ${hidden.size} masked, ${screenshot.width}x${screenshot.height}")
        val out = screenshot.copy(Bitmap.Config.ARGB_8888, true)
        if (hidden.isNotEmpty()) {
            val canvas = Canvas(out)
            val paint = Paint().apply { color = Color.BLACK }
            for (rect in hidden) canvas.drawRect(Rect(rect.left - PAD, rect.top - PAD, rect.right + PAD, rect.bottom + PAD), paint)
        }
        return out
    }

    /** Each node's place on the screen: its parent's content origin plus its own offset. */
    private fun walk(node: AssistStructure.ViewNode, parentX: Int, parentY: Int, texts: MutableList<Pair<String, Rect>>, secrets: MutableList<Rect>) {
        val x = parentX + node.left
        val y = parentY + node.top
        val rect = Rect(x, y, x + node.width, y + node.height)
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it to rect }
        if (password(node)) secrets += rect
        for (i in 0 until node.childCount) walk(node.getChildAt(i), x - node.scrollX, y - node.scrollY, texts, secrets)
    }

    private fun password(node: AssistStructure.ViewNode): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val klass = node.inputType and InputType.TYPE_MASK_CLASS
        val typed = klass == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val hinted = node.autofillHints?.any { hint -> hint.contains("password", ignoreCase = true) || hint.contains("otp", ignoreCase = true) } == true
        return typed || hinted
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val longer = max(bitmap.width, bitmap.height)
        val scaled = if (longer > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longer
            bitmap.scale((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
        } else {
            bitmap
        }
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private const val PAD = 4

    private val PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    const val CANT_SEE = "Anu can't see this screen: the app keeps it private, or the assistant's \"Use screenshot\" setting is off."
    const val CANT_CHECK = "Anu can't check this screen for codes, so it won't read it. Turn on the assistant's \"Use text from screen\" setting."
}
