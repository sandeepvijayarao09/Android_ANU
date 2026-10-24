package io.github.sandeepvijayarao09.anu.connector.apps

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.sandeepvijayarao09.anu.core.agent.AppDriver
import io.github.sandeepvijayarao09.anu.core.agent.AppScreen
import io.github.sandeepvijayarao09.anu.core.agent.LaunchedApp
import io.github.sandeepvijayarao09.anu.core.agent.ScreenElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * App control on Android (ADR-098): opens apps by the name their launcher icon shows, reads the active app's screen
 * through [AnuAccessibilityService] as numbered elements, and acts on them by their accessibility actions, with a tap
 * at the element's center when it has none. A password field's text is never read (I1). Nothing it reads is kept
 * after the task: [hideProgress] lets go of the screen.
 */
class AccessibilityAppDriver(private val context: Context) : AppDriver {
    private val service: AnuAccessibilityService? get() = AnuAccessibilityService.instance
    private val stop = MutableStateFlow(false)

    /** The nodes behind the last screen's element numbers. */
    private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override val ready: Boolean get() = service != null

    @Volatile
    override var current: AppScreen? = null
        private set

    override val stops: Flow<Unit> = stop.filter { it }.map { }

    override fun openSetup() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun open(name: String): LaunchedApp? = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        val launchers = packages.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).map { LaunchedApp(it.loadLabel(packages).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
        val wanted = name.trim().removeSuffix(" app").trim()
        // The exact name first, then one that starts with it, then the only one that contains it.
        val app = launchers.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
            ?: launchers.filter { it.label.startsWith(wanted, ignoreCase = true) }.singleOrNull()
            ?: launchers.filter { it.label.contains(wanted, ignoreCase = true) }.singleOrNull()
            ?: return@withContext null
        val launch = packages.getLaunchIntentForPackage(app.packageName) ?: return@withContext null
        service?.listenTo(app.packageName)
        stop.value = false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        // Wait until the app is in front, up to 5 s.
        val until = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < until && activeWindow()?.root?.packageName?.toString() != app.packageName) delay(100)
        app
    }

    override suspend fun read(): AppScreen? {
        settle()
        val root = activeWindow()?.root ?: service?.rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString() ?: return null
        val found = mutableListOf<ScreenElement>()
        val byId = mutableMapOf<Int, AccessibilityNodeInfo>()
        walk(root, found, byId, insideButton = false)
        nodes = byId
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return AppScreen(label, packageName, found).also { current = it }
    }

    /**
     * Adds [node]'s elements in reading order. A button carries its children's words as its label, so a product card
     * reads as one button; fields, switches and buttons inside it are still listed on their own.
     */
    private fun walk(node: AccessibilityNodeInfo, found: MutableList<ScreenElement>, byId: MutableMap<Int, AccessibilityNodeInfo>, insideButton: Boolean) {
        if (found.size >= MAX_ELEMENTS || !node.isVisibleToUser) return
        fun add(kind: ScreenElement.Kind, label: String, value: String? = null, checked: Boolean? = null) {
            val id = found.size + 1
            found += ScreenElement(id, kind, label.trim(), value, node.isPassword, checked)
            byId[id] = node
        }
        val own = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        var button = insideButton
        when {
            node.isEditable -> {
                val showingHint = node.isShowingHintText
                val label = node.hintText?.toString() ?: node.contentDescription?.toString() ?: node.labeledBy?.text?.toString()
                    ?: node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ') ?: "Text field"
                // A password field's text is never read (I1).
                val value = if (node.isPassword || showingHint) null else node.text?.toString()
                add(ScreenElement.Kind.FIELD, label, value)
            }
            node.isCheckable -> {
                val kind = if (node.className?.contains("Switch") == true) ScreenElement.Kind.SWITCH else ScreenElement.Kind.CHECKBOX
                add(kind, own.ifEmpty { words(node) }.ifEmpty { "Option" }, checked = node.isChecked)
            }
            node.isClickable && !insideButton -> {
                val label = own.ifEmpty { words(node) }.ifEmpty { node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ').orEmpty() }
                if (label.isNotEmpty()) {
                    add(ScreenElement.Kind.BUTTON, label)
                    button = true
                }
            }
            node.isScrollable && found.none { it.kind == ScreenElement.Kind.LIST } -> add(ScreenElement.Kind.LIST, node.contentDescription?.toString() ?: "List")
            own.isNotEmpty() && !insideButton -> add(ScreenElement.Kind.TEXT, own)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, found, byId, button) }
    }

    /** The words under [node], joined, for a button whose words are its children's: "Trail Runner 2 · $120". */
    private fun words(node: AccessibilityNodeInfo, limit: Int = 4): String {
        val out = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo) {
            if (out.size >= limit || !n.isVisibleToUser || n.isPassword) return
            (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(::collect)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        return out.joinToString(" · ")
    }

    override suspend fun tap(id: Int) {
        val node = node(id)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        gesture(node, Path().apply { center(node).let { (x, y) -> moveTo(x, y) } }, 60)
    }

    override suspend fun type(id: Int, text: String) {
        val node = node(id)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "the field didn't take the text" }
    }

    override suspend fun enter(id: Int) {
        check(node(id).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) { "the field has no enter action" }
    }

    override suspend fun scroll(down: Boolean) {
        val list = nodes.values.firstOrNull { it.isScrollable }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (list?.performAction(action) == true) return
        // No list says it scrolls: swipe through the middle of the screen.
        val size = context.resources.displayMetrics
        val x = size.widthPixels / 2f
        val (from, to) = if (down) size.heightPixels * 0.7f to size.heightPixels * 0.3f else size.heightPixels * 0.3f to size.heightPixels * 0.7f
        gesture(null, Path().apply { moveTo(x, from); lineTo(x, to) }, 300)
    }

    override suspend fun back() {
        check(service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true) { "back didn't work" }
    }

    override fun showProgress(app: String, line: String) {
        service?.pill?.show(app, line) { stop.value = true }
    }

    override fun hideProgress() {
        service?.pill?.hide()
        service?.listenTo(null)
        nodes = emptyMap()
        current = null
        stop.value = false
    }

    private fun node(id: Int): AccessibilityNodeInfo = nodes[id]?.also { it.refresh() } ?: error("element [$id] is gone from the screen")

    /** The app window that has focus, not Anu's own line or the keyboard. */
    private fun activeWindow(): AccessibilityWindowInfo? =
        service?.windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: service?.windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }

    /** Waits until the app's screen has been still for [QUIET_MS], at most [SETTLE_MAX_MS]. */
    private suspend fun settle() {
        val service = service ?: return
        val started = SystemClock.uptimeMillis()
        delay(QUIET_MS)
        while (SystemClock.uptimeMillis() - service.lastChangeAt < QUIET_MS && SystemClock.uptimeMillis() - started < SETTLE_MAX_MS) delay(50)
    }

    private fun center(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val bounds = Rect().also(node::getBoundsInScreen)
        return bounds.exactCenterX() to bounds.exactCenterY()
    }

    private fun gesture(node: AccessibilityNodeInfo?, path: Path, millis: Long) {
        val service = checkNotNull(service) { "app control is off" }
        val stroke = GestureDescription.StrokeDescription(path, 0, millis)
        check(service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)) {
            if (node != null) "couldn't tap that" else "couldn't scroll"
        }
    }

    private companion object {
        /** About a screenful, which keeps each step's prompt small on the phone. */
        const val MAX_ELEMENTS = 60
        const val QUIET_MS = 400L
        const val SETTLE_MAX_MS = 2_500L
    }
}
