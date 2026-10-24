package io.github.sandeepvijayarao09.anu.connector.apps

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The line at the top of the screen while Anu works in another app (ADR-098, DESIGN.md "Working in an app"): "Anu ·
 * Test Shop", what it's doing now, and Stop. A `full` pill on `surface`, with the soft shadow in light mode and a
 * hairline in dark. It's an accessibility overlay, so it needs no other permission and never takes the keyboard.
 */
internal class ProgressPill(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var title: TextView? = null
    private var line: TextView? = null

    fun show(app: String, doing: String, onStop: () -> Unit) = main.post {
        if (view == null) add(onStop)
        title?.text = "Anu · $app"
        line?.text = doing
        view?.contentDescription = "Anu is working in $app. $doing"
    }

    fun hide() = main.post {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
        title = null
        line = null
    }

    /** Lets touches through the line, or takes them again. Call on the main thread, before and after Anu's own tap. */
    fun touchable(on: Boolean) {
        val shown = view ?: return
        val params = shown.layoutParams as WindowManager.LayoutParams
        params.flags = if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windows.updateViewLayout(shown, params)
    }

    private fun add(onStop: () -> Unit) {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) TEXT_DARK else TEXT
        val muted = if (dark) MUTED_DARK else MUTED
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(if (dark) SURFACE_DARK else SURFACE)
                // Dark mode has no shadows: a white hairline at 6% marks the edge (DESIGN.md, Elevation & Depth).
                if (dark) setStroke(dp(1) / 2, Color.argb(15, 255, 255, 255))
            }
            if (!dark) elevation = dp(6).toFloat()
        }
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(context).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line = TextView(context).apply {
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        words.addView(title)
        words.addView(line)
        pill.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val stop = TextView(context).apply {
            text = context.getString(R.string.apps_stop)
            contentDescription = context.getString(R.string.apps_stop_description)
            setTextColor(if (dark) ON_PRIMARY_DARK else ON_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minWidth = dp(72)
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(if (dark) PRIMARY_DARK else PRIMARY)
            }
            setOnClickListener { onStop() }
        }
        // 48dp touch target around a 40dp button.
        pill.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(12) })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(44)
        }
        val holder = LinearLayout(context).apply {
            setPadding(dp(16), dp(4), dp(16), dp(12))
            addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        windows.addView(holder, params)
        view = holder
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        // DESIGN.md tokens: surface, text, text-muted, primary and on-primary, light and dark.
        val SURFACE = Color.parseColor("#FFFFFF")
        val SURFACE_DARK = Color.parseColor("#1C1C1E")
        val TEXT = Color.parseColor("#1D1D1F")
        val TEXT_DARK = Color.parseColor("#F5F5F7")
        val MUTED = Color.parseColor("#6E6E73")
        val MUTED_DARK = Color.parseColor("#98989D")
        val PRIMARY = Color.parseColor("#1D1D1F")
        val PRIMARY_DARK = Color.parseColor("#F5F5F7")
        val ON_PRIMARY = Color.parseColor("#FFFFFF")
        val ON_PRIMARY_DARK = Color.parseColor("#000000")
    }
}
