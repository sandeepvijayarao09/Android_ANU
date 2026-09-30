package io.github.sandeepvijayarao09.anu.feature.home

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The approval sheet finishes opening while the keyboard hides (ADR-086). On anu_api36, with the keyboard up
 * during a turn, the sheet took focus, the keyboard began hiding, and the sheet stayed invisible: its opening
 * ended on anchors the hiding keyboard had just moved. Each round here types into a field, then asks for
 * approval and hides the keyboard in the same frame, with busy threads as E4B's and an endless animation as
 * the thinking sphere's, which made 3 to 4 rounds in 8 fail before the fix. Arguments: `rounds` (8),
 * `burners` (4), `spin` (on), `hide` ("controller" hides it in the same frame; "off" leaves it to the focus change).
 */
@RunWith(AndroidJUnit4::class)
class ApprovalSheetImeTest {

    private val request = ApprovalRequest(
        id = "r1",
        toolName = "memory_remember",
        summary = "Remember: groceries are oat milk, eggs and spinach",
        payload = buildJsonObject { put("fact", "Groceries: oat milk, eggs, spinach") },
        risk = RiskTier.WRITE_PRIVATE,
        reason = "This chat shows a note from a service, which others can write, so check this closely",
        untrusted = setOf(UntrustedContent.SERVICE),
    )

    private val args = InstrumentationRegistry.getArguments()
    private fun arg(name: String, default: String) = args.getString(name) ?: default

    private val roots = CopyOnWriteArrayList<ViewRootForTest>()

    @After fun forgetRoots() {
        ViewRootForTest.onViewCreatedCallback = null
    }

    @Test
    fun theSheetFinishesOpeningAsTheKeyboardHides() {
        val rounds = arg("rounds", "8").toInt()
        val spin = arg("spin", "on") == "on"
        val hideSameFrame = arg("hide", "controller") == "controller"
        val burning = AtomicBoolean(true)
        val burners = List(arg("burners", "4").toInt()) {
            Thread { var x = 0L; while (burning.get()) x += System.nanoTime() % 7 }.apply { isDaemon = true; start() }
        }
        ViewRootForTest.onViewCreatedCallback = { roots += it }
        val approval = mutableStateOf<ApprovalRequest?>(null)
        val focus = FocusRequester()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var activity: ComponentActivity
        scenario.onActivity { a ->
            activity = a
            a.setContent {
                var text by remember { mutableStateOf("Remember the groceries in my Notes") }
                AnuTheme(dark = false) {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        if (spin) {
                            val turn by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(2_400, easing = LinearEasing)))
                            Canvas(Modifier.padding(20.dp).size(26.dp)) { drawArc(Color.Gray, turn, 90f, useCenter = true) }
                        }
                        Spacer(Modifier.weight(1f))
                        BasicTextField(text, { text = it }, Modifier.fillMaxWidth().padding(20.dp).focusRequester(focus))
                    }
                    ConversationSheets(ChatUiState(approval = approval.value), {}, {}, { _, _ -> }, {}, {})
                }
            }
        }
        val outcomes = mutableListOf<String>()
        try {
            repeat(rounds) { round ->
                scenario.onActivity { a ->
                    focus.requestFocus()
                    WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.ime())
                }
                val typing = waitFor(8_000) { keyboardUp(activity) }
                SystemClock.sleep(700)
                // The broker asks: the sheet opens and takes focus, and the keyboard hides.
                scenario.onActivity { a ->
                    approval.value = request
                    if (hideSameFrame) WindowCompat.getInsetsController(a.window, a.window.decorView).hide(WindowInsetsCompat.Type.ime())
                }
                // Open means settled at the bottom of the screen once the keyboard is gone. Rising, the sheet
                // passes the bottom on its way, so it must stay there for a second.
                var since = 0L
                var last: Pair<Int, Int>? = null
                val open = waitFor(20_000) {
                    val now = declineBounds()
                    if (now != last) { last = now; since = SystemClock.uptimeMillis() }
                    !keyboardUp(activity) && atBottom(now) && SystemClock.uptimeMillis() - since >= 1_000
                }
                val outcome = "round $round: keyboard was up=$typing, sheet open=$open, Don't button at ${declineBounds()}"
                Log.d(TAG, outcome)
                outcomes += outcome
                scenario.onActivity { approval.value = null }
                SystemClock.sleep(800)
            }
        } finally {
            burning.set(false)
            burners.forEach { it.join(1_000) }
            scenario.close()
        }
        assertEquals(outcomes.joinToString("\n"), rounds, outcomes.count { "sheet open=true" in it })
    }

    /** The decline button's bounds in the sheet's window, from its semantics, or null while no sheet shows it. */
    private fun declineBounds(): Pair<Int, Int>? {
        var bounds: Pair<Int, Int>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            roots.filter { it.view.isAttachedToWindow }.forEach { root ->
                val window = root.view.rootView.height
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                    .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Don't" } == true }
                    ?.let { bounds = it.boundsInWindow.bottom.toInt() to window }
            }
        }
        return bounds
    }

    /** The sheet sits at the bottom: its last button ends in the window's lowest sixth, not above it or below it. */
    private fun atBottom(bounds: Pair<Int, Int>?): Boolean {
        val (bottom, window) = bounds ?: return false
        return bottom in (window - window / 6)..window
    }

    private fun keyboardUp(activity: ComponentActivity): Boolean {
        var up = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            up = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return up
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    private companion object {
        const val TAG = "AnuSheetIme"
    }
}
