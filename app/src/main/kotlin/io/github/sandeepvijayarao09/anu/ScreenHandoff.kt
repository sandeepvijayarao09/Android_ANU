package io.github.sandeepvijayarao09.anu

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.text.InputType
import android.util.Log
import androidx.core.graphics.scale
import io.github.sandeepvijayarao09.anu.core.model.Screen
import io.github.sandeepvijayarao09.anu.core.security.OneTimeCodes
import io.github.sandeepvijayarao09.anu.connector.messages.MessagingHub
import io.github.sandeepvijayarao09.anu.feature.home.ScreenChat
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
 * it when it closes. It's read only when the user asks about the screen. The Quick Settings tile hands over a screen
 * it read through app control instead, masked the same way, or why it couldn't read one ([give], ADR-108).
 */
object ScreenHandoff : ScreenSource {
    private class Parts(val atMillis: Long) {
        @Volatile var structure: AssistStructure? = null
        @Volatile var structureDone = false
        @Volatile var screenshot: Bitmap? = null
        @Volatile var screenshotDone = false
    }

    @Volatile private var parts: Parts? = null

    /** The tile's screen, already masked, or why there's none (ADR-108). */
    @Volatile private var given: ScreenLook? = null

    /** How long a question waits for parts still arriving, since Android hands them over just after the summon. */
    private const val WAIT_MS = 2_000L

    /** Longest side of the picture the model gets, as for photos (ADR-075). */
    private const val MAX_SIDE = 1_024

    private lateinit var appContext: Context

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun begin(atMillis: Long) {
        given = null
        parts = Parts(atMillis)
    }

    /** The tile's screen, or why it couldn't be read, for the card it opens next (ADR-108). */
    fun give(look: ScreenLook) {
        parts = null
        given = look
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
    override fun offered(): Boolean = parts != null || given is ScreenLook.Ready

    override fun note(): String? = (given as? ScreenLook.Unavailable)?.reason

    fun clear() {
        parts = null
        given = null
    }

    override suspend fun screen(): ScreenLook {
        given?.let { return it }
        val held = parts ?: return ScreenLook.Unavailable(CANT_SEE)
        withTimeoutOrNull(WAIT_MS) { while (!(held.structureDone && held.screenshotDone)) delay(50) }
        val screenshot = held.screenshot ?: return ScreenLook.Unavailable(CANT_SEE)
        // Without the screen's text, Anu can't find codes on it to mask, so it won't read it (SECRETS-1, ADR-082).
        val structure = held.structure ?: return ScreenLook.Unavailable(CANT_CHECK)
        val masked = withContext(Dispatchers.Default) { masked(structure, screenshot) }
        return ready(structure.activityComponent?.packageName, held.atMillis, masked)
    }

    /**
     * A screen read through app control for the tile (ADR-108): [texts] and [secrets] are placed in [screenshot]'s
     * pixels, as the assist structure's are once walked, and masked the same way.
     */
    suspend fun fromTile(packageName: String, texts: List<Pair<String, Rect>>, secrets: List<Rect>, screenshot: Bitmap): ScreenLook {
        val masked = withContext(Dispatchers.Default) { masked(texts, secrets, screenshot) }
        return ready(packageName, System.currentTimeMillis(), masked)
    }

    private fun ready(pkg: String?, atMillis: Long, masked: Masked): ScreenLook.Ready {
        val app = pkg?.let { name ->
            runCatching { appContext.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(name, 0)).toString() } }.getOrNull()
        } ?: "this app"
        // The chat the screen shows, among those seen in notifications, for a reply drafted about it (ADR-092).
        val chat = pkg?.let { MessagingHub.store.shownIn(it, masked.texts) }
        // Whether one was found and could be answered, never which (the plan's pass bar counts these).
        if (BuildConfig.DEBUG) Log.d("Anu", "Screen: chat ${if (chat != null) "found" else "not found"}, reply action ${if (chat?.reply != null) "yes" else "no"}")
        return ScreenLook.Ready(
            Screen(app, atMillis, jpeg(masked.picture), secrets = masked.codes),
            chat?.let { ScreenChat(it.key, it.title, it.appLabel) },
        )
    }

    /** A screenshot with its secrets blacked out, the codes found, and the screen's texts, all in memory only. */
    internal class Masked(val picture: Bitmap, val codes: List<String>, val texts: List<String>)

    /**
     * [screenshot] with its secrets blacked out, found from the screen's own text: every text that holds a code when the
     * screen speaks of one (as `OneTimeCodes` masks messages), and every password field. Also the codes themselves, for
     * masking the answer if one comes back anyway, and the screen's texts, for finding the chat it shows; they stay in
     * memory, and neither reaches a model.
     */
    internal fun masked(structure: AssistStructure, screenshot: Bitmap): Masked {
        val texts = mutableListOf<Pair<String, Rect>>()
        val secrets = mutableListOf<Rect>()
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i)
            walk(window.rootViewNode, Matrix().apply { setTranslate(window.left.toFloat(), window.top.toFloat()) }, texts, secrets)
        }
        if (BuildConfig.DEBUG) {
            val windows = (0 until structure.windowNodeCount).map { structure.getWindowNodeAt(it) }.joinToString { "${it.left},${it.top} ${it.width}x${it.height}" }
            Log.d("Anu", "Screen: windows $windows")
        }
        return masked(texts, secrets, screenshot)
    }

    /** [screenshot] with [secrets] and the texts [ScreenSecrets] finds blacked out. */
    private fun masked(texts: List<Pair<String, Rect>>, secrets: List<Rect>, screenshot: Bitmap): Masked {
        val found = ScreenSecrets.find(texts, secrets)
        // Counts and places only, never what the screen says.
        if (BuildConfig.DEBUG) {
            Log.d("Anu", "Screen: ${texts.size} texts, ${found.hidden.size} masked, ${screenshot.width}x${screenshot.height}")
            Log.d("Anu", "Screen: masked ${found.hidden.joinToString { it.toShortString() }}")
        }
        val out = screenshot.copy(Bitmap.Config.ARGB_8888, true)
        if (found.hidden.isNotEmpty()) {
            val canvas = Canvas(out)
            val paint = Paint().apply { color = Color.BLACK }
            for (rect in found.hidden) canvas.drawRect(Rect(rect.left - PAD, rect.top - PAD, rect.right + PAD, rect.bottom + PAD), paint)
        }
        return Masked(out, found.codes, texts.map { it.first })
    }

    /**
     * Each node's place on the screen, as Android draws it: its parent's content, moved to the node's position, then by
     * the node's own transformation (a translation such as a collapsing app bar's, or a scale), less its scroll for
     * its children. Ignoring the transformation put a mask 612 px below a code in the Files app, so it was missed.
     */
    private fun walk(
        node: AssistStructure.ViewNode,
        parent: Matrix,
        texts: MutableList<Pair<String, Rect>>,
        secrets: MutableList<Rect>,
    ) {
        val matrix = Matrix(parent).apply {
            preTranslate(node.left.toFloat(), node.top.toFloat())
            node.transformation?.let { preConcat(it) }
        }
        val box = RectF(0f, 0f, node.width.toFloat(), node.height.toFloat())
        matrix.mapRect(box)
        val rect = Rect().also { box.roundOut(it) }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it to rect }
        if (password(node)) secrets += rect
        val inner = Matrix(matrix).apply { preTranslate(-node.scrollX.toFloat(), -node.scrollY.toFloat()) }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), inner, texts, secrets)
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

    const val PRIVATE = "Anu can't see this screen: the app keeps it private."
    const val CANT_SEE = "Anu can't see this screen: the app keeps it private, or the assistant's \"Use screenshot\" setting is off."
    const val CANT_CHECK = "Anu can't check this screen for codes, so it won't read it. Turn on the assistant's \"Use text from screen\" setting."
}

/**
 * What to black out of a screen, found from its own texts (SECRETS-1, ADR-082): every password field, and every text
 * that holds a code when the screen speaks of one, as `OneTimeCodes` masks messages; and the codes, for masking an
 * answer that repeats one. [B] is where each sits on the screen.
 */
internal object ScreenSecrets {
    class Found<B>(val hidden: List<B>, val codes: List<String>)

    fun <B> find(texts: List<Pair<String, B>>, secrets: List<B>): Found<B> {
        val verification = OneTimeCodes.looksLikeVerification(texts.joinToString("\n") { it.first })
        val coded = if (verification) texts.filter { OneTimeCodes.containsCode(it.first) } else emptyList()
        return Found(secrets + coded.map { it.second }, coded.flatMap { OneTimeCodes.codes(it.first) }.distinct())
    }
}
