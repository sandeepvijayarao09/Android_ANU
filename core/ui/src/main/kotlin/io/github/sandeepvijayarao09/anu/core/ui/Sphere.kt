package io.github.sandeepvijayarao09.anu.core.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What Anu is doing, as the sphere shows it. Every state follows a real event (DESIGN.md). */
enum class SphereState { IDLE, LISTENING, THINKING, SPEAKING }

/** True when the system says to remove animations. The sphere then stays still. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Anu's presence (DESIGN.md): a procedural pearl in light mode and obsidian glass in dark. No face, no
 * mascot.
 * - It breathes when idle, follows the voice [level] while listening, turns its light while thinking,
 *   and pulses while speaking.
 * - Each time [awayPulse] changes, one orange arc sweeps around the rim: something just left the phone.
 * - [shadow] draws the contact shadow below it, for the large sizes.
 */
@Composable
fun Sphere(
    state: SphereState,
    size: Dp,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    awayPulse: Int = 0,
    shadow: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = AnuTheme.colors
    val still = rememberReducedMotion()
    val voice by animateFloatAsState(if (state == SphereState.LISTENING) level else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "sphere-level")
    val time = rememberInfiniteTransition(label = "sphere")
    val breath by time.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "breath")
    val turn by time.animateFloat(0f, 360f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "turn")
    val pulse by time.animateFloat(0f, 1f, infiniteRepeatable(tween(460), RepeatMode.Reverse), label = "pulse")
    val arc = remember { Animatable(1f) }
    LaunchedEffect(awayPulse) {
        if (awayPulse != 0) {
            arc.snapTo(0f)
            arc.animateTo(1f, tween(1_100, easing = LinearEasing))
        }
    }
    val scale = if (still) 1f else when (state) {
        SphereState.IDLE -> 1f + 0.015f * breath
        SphereState.LISTENING -> 1f + 0.045f * voice
        SphereState.THINKING -> 1f
        SphereState.SPEAKING -> 1f + 0.025f * pulse
    }
    val label = when (state) {
        SphereState.IDLE -> "Ready"
        SphereState.LISTENING -> "Listening"
        SphereState.THINKING -> "Thinking"
        SphereState.SPEAKING -> "Speaking"
    }
    val base = modifier
        .size(size, if (shadow) size * 1.16f else size)
        .semantics {
            contentDescription = "Anu"
            stateDescription = label
            if (onClick != null) role = Role.Button
        }
    val tappable = if (onClick != null) base.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick) else base
    Canvas(tappable) {
        val d = this.size.width
        val r = d / 2f * 0.9f
        val c = Offset(d / 2f, d / 2f)
        if (shadow) drawContactShadow(c, r, colors.isDark)
        scale(scale, pivot = c) {
            if (colors.isDark) drawObsidian(c, r, colors, state, voice, if (still) 0f else turn) else drawPearl(c, r, colors, state, voice, if (still) 0f else turn)
        }
        if (arc.value < 1f) drawAwayArc(c, r * scale, colors.away, arc.value)
    }
}

private fun DrawScope.drawContactShadow(c: Offset, r: Float, dark: Boolean) {
    val center = Offset(c.x, c.y + r * 1.22f)
    val tint = if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.20f)
    scale(scaleX = 2.8f, scaleY = 0.42f, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(tint, Color.Transparent), center, r * 0.26f), r * 0.26f, center)
    }
}

/** Light mode: a pearl lit from the upper left, with a faint cool tint and an inner shade. */
private fun DrawScope.drawPearl(c: Offset, r: Float, colors: AnuColors, state: SphereState, voice: Float, turn: Float) {
    drawCircle(
        Brush.radialGradient(0f to Color(0xFFFCFCFD), 0.52f to Color(0xFFECEDF1), 1f to Color(0xFFCDD0D8), center = c + Offset(0f, -0.04f * r), radius = r * 1.02f),
        r, c,
    )
    val tintAt = tintCenter(c, r, state, turn, lightMode = true)
    val tintAlpha = if (state == SphereState.LISTENING) 0.10f + 0.28f * voice else 0.10f
    drawCircle(Brush.radialGradient(listOf(colors.accent.copy(alpha = tintAlpha), Color.Transparent), tintAt, r * 0.84f), r, c)
    drawCircle(Brush.radialGradient(0.55f to Color.Transparent, 1f to Color(0x1A12141C), center = c + Offset(-0.2f * r, -0.25f * r), radius = r * 1.35f), r, c)
    val highlight = c + Offset(-0.34f * r, -0.46f * r)
    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.98f), 0.33f to Color.White.copy(alpha = 0.98f), 1f to Color.Transparent, center = highlight, radius = r * 0.42f), r * 0.42f, highlight)
}

/** Dark mode: obsidian glass with a small crisp highlight, a rim of light from below, and a hairline. */
private fun DrawScope.drawObsidian(c: Offset, r: Float, colors: AnuColors, state: SphereState, voice: Float, turn: Float) {
    drawCircle(
        Brush.radialGradient(0f to Color(0xFF3A3B41), 0.58f to Color(0xFF18181B), 1f to Color(0xFF060607), center = c + Offset(-0.12f * r, -0.2f * r), radius = r * 1.1f),
        r, c,
    )
    val tintAt = tintCenter(c, r, state, turn, lightMode = false)
    val tintAlpha = if (state == SphereState.LISTENING) 0.25f + 0.35f * voice else 0.22f
    drawCircle(Brush.radialGradient(listOf(colors.accent.copy(alpha = tintAlpha), Color.Transparent), tintAt, r * 0.9f), r, c)
    val rim = c + Offset(0f, 1.16f * r)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), rim, r * 0.76f), r, c)
    val highlight = c + Offset(-0.28f * r, -0.52f * r)
    scale(scaleX = 1.6f, scaleY = 1f, pivot = highlight) {
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f), Color.Transparent), highlight, r * 0.13f), r * 0.13f, highlight)
    }
    drawCircle(Color.White.copy(alpha = 0.08f), r, c, style = Stroke(width = 1.dp.toPx()))
}

/** Where the inner light sits: resting at the lower right, centered while listening, circling while thinking. */
private fun tintCenter(c: Offset, r: Float, state: SphereState, turn: Float, lightMode: Boolean): Offset = when (state) {
    SphereState.LISTENING -> c + Offset(0.08f * r, 0.16f * r)
    SphereState.THINKING -> {
        val a = turn / 180f * PI.toFloat()
        c + Offset(cos(a) * 0.45f * r, sin(a) * 0.45f * r)
    }
    else -> if (lightMode) c + Offset(0.36f * r, 0.52f * r) else c + Offset(0.24f * r, 0.4f * r)
}

/** One orange arc sweeping once around the rim when data leaves the phone. */
private fun DrawScope.drawAwayArc(c: Offset, r: Float, away: Color, progress: Float) {
    val ring = r + 5.dp.toPx()
    val alpha = sin(progress * PI.toFloat()).coerceIn(0f, 1f)
    drawArc(
        color = away.copy(alpha = alpha),
        startAngle = -90f + 360f * progress - 60f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(c.x - ring, c.y - ring),
        size = Size(ring * 2, ring * 2),
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
    )
}
