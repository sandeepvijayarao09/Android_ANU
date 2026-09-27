package io.github.sandeepvijayarao09.anu.core.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes an activity's window the assistant card (DESIGN.md "Elevation & Depth"): a floating window the
 * size of the card, 10dp from the sides and 26dp from the bottom of the screen (at least 4dp above a
 * navigation bar taller than that), over a light dim. Tapping outside it closes the activity.
 *
 * Only the system's cross-window blur can blur another app, and it blurs within the window's background
 * shape, so the window is exactly the card: the app underneath is frosted behind the card only, under the
 * `material` tint. The system can turn blurs off at any time (battery saver, media tunneling, the
 * developer option, or a device without them), and then the card turns to the opaque surface color, so
 * its text always has a solid background. [onBlur] hears each change.
 *
 * The theme must make the window floating and translucent. Call this in `onCreate`, before `setContent`.
 * The size is worked out again when the activity is recreated, such as on rotation.
 */
fun Activity.useFrostedCardWindow(onBlur: (Boolean) -> Unit = {}) {
    val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val colors = if (dark) DarkColors else LightColors
    val density = resources.displayMetrics.density
    fun px(dp: Int) = (dp * density).roundToInt()
    // The window's blur lives on its decor view, which only exists once something asks for it.
    val decor = window.decorView

    val background = GradientDrawable().apply {
        cornerRadius = 28 * density
        // A hairline keeps the card's edge when what's behind it is the same color (DESIGN.md: no glow).
        setStroke(max(1, px(1) / 2), (if (dark) Color.White else Color.Black).copy(alpha = 0.06f).toArgb())
    }
    window.setBackgroundDrawable(background)

    val metrics = windowManager.currentWindowMetrics
    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val available = metrics.bounds.width() - insets.left - insets.right
    window.setLayout(min(available - px(20), px(640)), WindowManager.LayoutParams.WRAP_CONTENT)
    window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    // A floating window is placed above the navigation bar, so its offset counts from the bar's top.
    val bar = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
    window.attributes = window.attributes.apply { y = max(px(26) - bar, px(4)) }
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.setDimAmount(0.12f)
    setFinishOnTouchOutside(true)

    val radius = px(26)
    val apply = Consumer<Boolean> { enabled ->
        background.setColor((if (enabled) colors.material else colors.surface).toArgb())
        window.setBackgroundBlurRadius(if (enabled) radius else 0)
        onBlur(enabled)
    }
    // The first frame is already right; then follow the system as it turns blurs on and off.
    apply.accept(windowManager.isCrossWindowBlurEnabled)
    decor.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = windowManager.addCrossWindowBlurEnabledListener(mainExecutor, apply)

            override fun onViewDetachedFromWindow(view: View) = windowManager.removeCrossWindowBlurEnabledListener(apply)
        },
    )
}
