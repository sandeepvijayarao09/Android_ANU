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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        launch(app)
    }

    override suspend fun openPackage(packageName: String): LaunchedApp? = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        val info = runCatching { packages.getApplicationInfo(packageName, 0) }.getOrNull() ?: return@withContext null
        launch(LaunchedApp(packages.getApplicationLabel(info).toString(), packageName))
    }

    /** Starts [app], listens to it, and waits until it's in front, up to 5 s. */
    private suspend fun launch(app: LaunchedApp): LaunchedApp? {
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        service?.listenTo(app.packageName)
        stop.value = false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        val until = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < until && activeWindow()?.root?.packageName?.toString() != app.packageName) delay(100)
        return app
    }

    override suspend fun read(): AppScreen? {
        settle()
        // Compose doesn't always report a change as it draws: on the emulator a read 400 ms after a tap caught a list
        // whose tapped row was gone and whose next page wasn't drawn. Two reads that agree mean it has settled.
        var screen = snapshot() ?: return null
        val started = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - started < SETTLE_MAX_MS) {
            delay(AGREE_MS)
            val again = snapshot() ?: return null
            if (again.first == screen.first) break
            screen = again
        }
        nodes = screen.second
        return screen.first.also { current = it }
    }

    /** The active app's screen now, with the nodes behind its numbers. */
    private fun snapshot(): Pair<AppScreen, Map<Int, AccessibilityNodeInfo>>? {
        // The focused app window, or the system's active root when that reads richer: on Amazon's results one read 10
        // elements where the page held 1,766 nodes.
        val roots = listOfNotNull(activeWindow()?.root, service?.rootInActiveWindow).distinct()
        val (root, found, byId) = roots.map { root ->
            val found = mutableListOf<ScreenElement>()
            val byId = mutableMapOf<Int, AccessibilityNodeInfo>()
            walk(root, found, byId, insideButton = false)
            Triple(root, found, byId)
        }.maxByOrNull { it.second.size } ?: return null
        val packageName = root.packageName?.toString() ?: return null
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return AppScreen(label, packageName, found) to byId
    }

    /**
     * Adds [node]'s elements in reading order. A button carries its children's words as its label, so a product card
     * reads as one button; fields, switches and buttons inside it are still listed on their own.
     */
    private fun walk(node: AccessibilityNodeInfo, found: MutableList<ScreenElement>, byId: MutableMap<Int, AccessibilityNodeInfo>, insideButton: Boolean) {
        if (found.size >= MAX_ELEMENTS) return
        // A wrapper can say it isn't visible while its children are on screen, as Amazon's do: its branch is still read,
        // and an element is listed when it's visible or its bounds are on the screen.
        val shows = node.isVisibleToUser || onScreen(node)
        fun add(kind: ScreenElement.Kind, label: String, value: String? = null, checked: Boolean? = null) {
            if (!shows) return
            val id = found.size + 1
            found += ScreenElement(id, kind, label.trim(), value, node.isPassword, checked)
            byId[id] = node
        }
        val own = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        var button = insideButton
        when {
            node.isEditable -> {
                val showingHint = node.isShowingHintText
                // Compose draws a field's label as its child, with no hint: those words name it.
                val label = node.hintText?.toString() ?: node.contentDescription?.toString() ?: node.labeledBy?.text?.toString()
                    ?: words(node).takeIf { it.isNotEmpty() } ?: node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ') ?: "Text field"
                // A password field's text is never read (I1).
                val value = if (node.isPassword || showingHint) null else node.text?.toString()
                add(ScreenElement.Kind.FIELD, label, value)
                button = true
            }
            node.isCheckable -> {
                val kind = if (node.className?.contains("Switch") == true) ScreenElement.Kind.SWITCH else ScreenElement.Kind.CHECKBOX
                add(kind, own.ifEmpty { words(node) }.ifEmpty { "Option" }, checked = node.isChecked)
                // Its words are its label: listing them again as text gave two "Size 10"s, and a tap on the text toggled it.
                button = true
            }
            // Amazon's suggestions are Buttons that say they aren't clickable: the app handles the touch itself.
            // A clickable container with buttons inside isn't one button: on Amazon's results, one held the whole list.
            tappable(node) && !insideButton && !holdsTappable(node, CONTAINER_DEPTH) -> {
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

    /** Whether [node] has a size and lies at least partly on the screen. */
    private fun onScreen(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        val size = context.resources.displayMetrics
        return bounds.width() > 0 && bounds.height() > 0 && bounds.intersects(0, 0, size.widthPixels, size.heightPixels)
    }

    /** Whether a finger would tap [node] as one thing. */
    private fun tappable(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.className?.endsWith("Button") == true

    /** Whether something under [node], at most [depth] levels down, can be tapped or typed into on its own. */
    private fun holdsTappable(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth == 0) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (tappable(child) || child.isEditable || child.isCheckable || holdsTappable(child, depth - 1)) return true
        }
        return false
    }

    /** The words under [node], joined, for a button whose words are its children's: "Trail Runner 2 · $120". */
    private fun words(node: AccessibilityNodeInfo, limit: Int = 4): String {
        // A field's own text is its value, never its label; a password's is never read (I1).
        val out = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo) {
            if (out.size >= limit || !n.isVisibleToUser || n.isPassword || n.isEditable) return
            (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(::collect)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        return out.joinToString(" · ")
    }

    /**
     * A tap where the element shows, as a finger would. Amazon's suggestions and result cards took the click action,
     * said it worked, and did nothing, so the click action is kept only for an element that's off the screen.
     */
    override suspend fun tap(id: Int) {
        val node = node(id)
        val bounds = Rect().also(node::getBoundsInScreen)
        val size = context.resources.displayMetrics
        if (!bounds.intersect(0, 0, size.widthPixels, size.heightPixels) || bounds.isEmpty) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "couldn't tap that" }
            return
        }
        gesture(node, Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }, 60)
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

    /** Performs [path] as a touch, with Anu's line letting touches through, so one under it reaches the app. */
    private suspend fun gesture(node: AccessibilityNodeInfo?, path: Path, millis: Long) {
        val service = checkNotNull(service) { "app control is off" }
        val stroke = GestureDescription.StrokeDescription(path, 0, millis)
        val finished = CompletableDeferred<Boolean>()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finished.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finished.complete(false)
            }
        }
        withContext(Dispatchers.Main) { service.pill.touchable(false) }
        // The window manager applies the change a little later; a tap sent at once still hit the line.
        delay(PILL_AWAY_MS)
        try {
            val sent = withContext(Dispatchers.Main) { service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, null) }
            check(sent && withTimeoutOrNull(millis + 2_000) { finished.await() } == true) { if (node != null) "couldn't tap that" else "couldn't scroll" }
        } finally {
            withContext(Dispatchers.Main) { service.pill.touchable(true) }
        }
    }

    private companion object {
        /** Enough for a busy page such as Amazon's results; the model sees only the part that bears on the goal. */
        const val MAX_ELEMENTS = 150

        /** How deep a clickable's children are searched for buttons of their own. */
        const val CONTAINER_DEPTH = 4
        const val QUIET_MS = 400L
        const val AGREE_MS = 250L
        const val PILL_AWAY_MS = 200L
        const val SETTLE_MAX_MS = 2_500L
    }
}
