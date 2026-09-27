package io.github.sandeepvijayarao09.anu.core.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
 * Anu's presence (DESIGN.md): a pearl in light mode and obsidian glass in dark. No face, no mascot.
 * - It breathes when idle, follows the voice [level] while listening, turns its inner light while
 *   thinking, and pulses while speaking.
 * - Each time [awayPulse] changes, one orange arc sweeps around the rim: something just left the phone.
 * - Each time [errorPulse] changes, it greys briefly: a turn failed, and the message says why.
 * - [shadow] draws the contact shadow below it, for the large sizes.
 *
 * On Android 13+ it is an AGSL shader, lit per pixel, with ripples on its surface that follow the voice
 * and an inner light bent by the glass. Android 12 draws the same materials with gradients.
 */
@Composable
fun Sphere(
    state: SphereState,
    size: Dp,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    awayPulse: Int = 0,
    errorPulse: Int = 0,
    shadow: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = AnuTheme.colors
    val still = rememberReducedMotion()
    // Read only while drawing, so the sphere redraws every frame without recomposing.
    val voice = animateFloatAsState(if (state == SphereState.LISTENING) level else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "voice")
    val listening = animateFloatAsState(if (state == SphereState.LISTENING) 1f else 0f, tween(300), label = "listening")
    val thinking = animateFloatAsState(if (state == SphereState.THINKING) 1f else 0f, tween(300), label = "thinking")
    val time = rememberInfiniteTransition(label = "sphere")
    val breath = time.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "breath")
    val turn = time.animateFloat(0f, 360f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "turn")
    val pulse = time.animateFloat(0f, 1f, infiniteRepeatable(tween(460), RepeatMode.Reverse), label = "pulse")
    val wave = time.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "wave")
    val arc = remember { Animatable(1f) }
    LaunchedEffect(awayPulse) {
        if (awayPulse != 0) {
            arc.snapTo(0f)
            arc.animateTo(1f, tween(1_100, easing = LinearEasing))
        }
    }
    val grey = remember { Animatable(0f) }
    LaunchedEffect(errorPulse) {
        if (errorPulse != 0) {
            if (still) grey.snapTo(1f) else grey.animateTo(1f, tween(180))
            delay(if (still) 1_200 else 600)
            if (still) grey.snapTo(0f) else grey.animateTo(0f, tween(700))
        }
    }
    val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) remember { ShaderSphere() } else null
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
        val scale = if (still) 1f else when (state) {
            SphereState.IDLE -> 1f + 0.015f * breath.value
            SphereState.LISTENING -> 1f + 0.045f * voice.value
            SphereState.THINKING -> 1f
            SphereState.SPEAKING -> 1f + 0.025f * pulse.value
        }
        val light = innerLight(colors.isDark, listening.value, thinking.value, if (still) 0f else turn.value)
        val glow = innerGlow(colors.isDark, state, voice.value, if (still) 0f else pulse.value)
        if (shadow) drawContactShadow(c, r, colors.isDark)
        if (shader != null) {
            shader.draw(this, c, r * scale, colors, light, glow, ripple = if (still) 0f else voice.value, phase = if (still) 0f else wave.value, grey = grey.value)
        } else {
            val a = colors.accent
            val y = 0.2126f * a.red + 0.7152f * a.green + 0.0722f * a.blue
            val accent = lerp(a, Color(y, y, y), grey.value)
            val tintAt = c + Offset(light.x * r, -light.y * r)
            scale(scale, pivot = c) {
                if (colors.isDark) drawObsidian(c, r, accent, tintAt, glow) else drawPearl(c, r, accent, tintAt, glow)
            }
        }
        if (arc.value < 1f) drawAwayArc(c, r * scale, colors.away, arc.value)
    }
}

/**
 * Where the inner light sits, on the unit disk with y up: resting at the lower right, drifting to the
 * middle while listening, and circling once every 2.4 s while thinking. It glides between them.
 */
private fun innerLight(dark: Boolean, listening: Float, thinking: Float, turnDegrees: Float): Offset {
    val rest = if (dark) Offset(0.24f, -0.40f) else Offset(0.36f, -0.52f)
    val listen = Offset(0.08f, -0.16f)
    val a = turnDegrees / 180f * PI.toFloat()
    val orbit = Offset(cos(a) * 0.45f, -sin(a) * 0.45f)
    return androidx.compose.ui.geometry.lerp(androidx.compose.ui.geometry.lerp(rest, listen, listening), orbit, thinking)
}

/** How bright the inner light is: brighter with the voice, a little brighter while thinking and speaking. */
private fun innerGlow(dark: Boolean, state: SphereState, voice: Float, pulse: Float): Float {
    val rest = if (dark) 0.26f else 0.14f
    return when (state) {
        SphereState.LISTENING -> rest + (if (dark) 0.40f else 0.30f) * voice
        SphereState.THINKING -> rest + 0.06f
        SphereState.SPEAKING -> rest + 0.06f * pulse
        SphereState.IDLE -> rest
    }
}

/** The shader sphere (Android 13+). Uniforms change every frame; each draw uses the values set before it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ShaderSphere {
    private val shader = RuntimeShader(SPHERE_SHADER)
    private val brush = ShaderBrush(shader)

    fun draw(scope: DrawScope, center: Offset, radius: Float, colors: AnuColors, light: Offset, glow: Float, ripple: Float, phase: Float, grey: Float) = with(scope) {
        shader.setFloatUniform("center", center.x, center.y)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("hairline", 1.dp.toPx() / radius)
        shader.setFloatUniform("phase", phase)
        shader.setFloatUniform("ripple", ripple)
        shader.setFloatUniform("light", light.x, light.y)
        shader.setFloatUniform("glow", glow)
        shader.setFloatUniform("grey", grey)
        shader.setFloatUniform("dark", if (colors.isDark) 1f else 0f)
        shader.setColorUniform("accent", colors.accent.toArgb())
        drawCircle(brush, radius + 2f, center)
    }
}

/**
 * The sphere in AGSL. A pixel's position on the unit disk gives the surface normal of a sphere facing
 * the viewer; a key light from the upper left shades it. The pearl wraps the light softly, with a broad
 * sheen and a bright core; obsidian is dark glass with a crisp highlight, light entering from below, a
 * cool reflection at the rim and a 1dp hairline. The inner light is a glow under the surface, bent
 * toward the rim by the glass. While listening, three waves move the surface normal, so the highlight
 * and the inner light ripple with the voice. An error mixes the color toward grey. Output is
 * premultiplied.
 */
private const val SPHERE_SHADER = """
uniform float2 center;
uniform float radius;
uniform float hairline;
uniform float phase;
uniform float ripple;
uniform float2 light;
uniform float glow;
uniform float grey;
uniform float dark;
layout(color) uniform half4 accent;

half4 main(float2 xy) {
    float2 p = (xy - center) / radius;
    p.y = -p.y;
    float rr = length(p);
    float aa = 1.2 / radius;
    float cover = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, rr);
    if (cover <= 0.0) {
        return half4(0.0);
    }
    float3 n = float3(p, sqrt(max(1.0 - rr * rr, 0.0)));
    if (ripple > 0.0) {
        float a = 5.0 * n.x + 2.0 * phase;
        float b = 4.0 * n.y - 3.0 * phase;
        float c = 7.0 * (n.x - n.y) + 5.0 * phase;
        float2 slope = float2(5.0 * cos(a) * sin(b) + 3.5 * cos(c), 4.0 * sin(a) * cos(b) - 3.5 * cos(c));
        n = normalize(n - float3(slope * (0.02 * ripple), 0.0));
    }
    // How far the ripples moved the surface: the inner light moves with it.
    float2 bend = n.xy - p;
    float3 key = normalize(float3(-0.5, 0.8, 0.33));
    float3 hv = normalize(key + float3(0.0, 0.0, 1.0));
    float ndl = dot(n, key);
    float ndh = max(dot(n, hv), 0.0);
    float edge = 1.0 - clamp(n.z, 0.0, 1.0);
    float3 acc = float3(accent.rgb);
    float3 color;
    if (dark < 0.5) {
        float wrap = clamp((ndl + 0.55) / 1.55, 0.0, 1.0);
        color = mix(float3(0.804, 0.816, 0.847), float3(0.988, 0.988, 0.992), smoothstep(0.05, 0.95, wrap));
        color *= 1.0 - 0.07 * smoothstep(0.55, 1.0, rr);
        float2 d = p + 1.2 * bend - light;
        color = mix(color, acc, glow * exp(-dot(d, d) / 0.30));
        color += 0.18 * pow(ndh, 8.0);
        color = mix(color, float3(1.0), 0.92 * smoothstep(0.90, 0.995, ndh));
    } else {
        float wrap = clamp((ndl + 0.35) / 1.35, 0.0, 1.0);
        color = mix(float3(0.024, 0.024, 0.027), float3(0.227, 0.231, 0.255), pow(wrap, 1.7));
        float2 d = p * (0.85 + 0.45 * edge) + 1.2 * bend - light;
        color += acc * (glow * exp(-dot(d, d) / 0.24));
        color += 0.16 * pow(edge, 2.0) * (1.0 - smoothstep(-0.95, 0.25, p.y));
        color += float3(0.55, 0.60, 0.72) * (0.07 * pow(edge, 3.0));
        color += 0.62 * pow(ndh, 90.0) + 0.06 * pow(ndh, 20.0);
        color = mix(color, float3(1.0), 0.08 * smoothstep(1.0 - 2.5 * hairline, 1.0 - 0.5 * hairline, rr));
    }
    color = mix(color, float3(dot(color, float3(0.2126, 0.7152, 0.0722))), grey);
    color = clamp(color, 0.0, 1.0);
    return half4(half3(color * cover), half(cover));
}
"""

private fun DrawScope.drawContactShadow(c: Offset, r: Float, dark: Boolean) {
    val center = Offset(c.x, c.y + r * 1.22f)
    val tint = if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.20f)
    scale(scaleX = 2.8f, scaleY = 0.42f, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(tint, Color.Transparent), center, r * 0.26f), r * 0.26f, center)
    }
}

/** Android 12's pearl: lit from the upper left, with a faint cool light inside and an inner shade. */
private fun DrawScope.drawPearl(c: Offset, r: Float, accent: Color, tintAt: Offset, glow: Float) {
    drawCircle(
        Brush.radialGradient(0f to Color(0xFFFCFCFD), 0.52f to Color(0xFFECEDF1), 1f to Color(0xFFCDD0D8), center = c + Offset(0f, -0.04f * r), radius = r * 1.02f),
        r, c,
    )
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = glow * 0.72f), Color.Transparent), tintAt, r * 0.84f), r, c)
    drawCircle(Brush.radialGradient(0.55f to Color.Transparent, 1f to Color(0x1A12141C), center = c + Offset(-0.2f * r, -0.25f * r), radius = r * 1.35f), r, c)
    val highlight = c + Offset(-0.34f * r, -0.46f * r)
    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.98f), 0.33f to Color.White.copy(alpha = 0.98f), 1f to Color.Transparent, center = highlight, radius = r * 0.42f), r * 0.42f, highlight)
}

/** Android 12's obsidian: dark glass with a small crisp highlight, a rim of light from below, and a hairline. */
private fun DrawScope.drawObsidian(c: Offset, r: Float, accent: Color, tintAt: Offset, glow: Float) {
    drawCircle(
        Brush.radialGradient(0f to Color(0xFF3A3B41), 0.58f to Color(0xFF18181B), 1f to Color(0xFF060607), center = c + Offset(-0.12f * r, -0.2f * r), radius = r * 1.1f),
        r, c,
    )
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = glow * 0.85f), Color.Transparent), tintAt, r * 0.9f), r, c)
    val rim = c + Offset(0f, 1.16f * r)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), rim, r * 0.76f), r, c)
    val highlight = c + Offset(-0.28f * r, -0.52f * r)
    scale(scaleX = 1.6f, scaleY = 1f, pivot = highlight) {
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f), Color.Transparent), highlight, r * 0.13f), r * 0.13f, highlight)
    }
    drawCircle(Color.White.copy(alpha = 0.08f), r, c, style = Stroke(width = 1.dp.toPx()))
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
