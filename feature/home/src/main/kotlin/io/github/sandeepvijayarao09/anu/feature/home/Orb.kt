package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The two colours of each state, blended across the sphere. */
private fun colors(phase: VoicePhase): Pair<Color, Color> = when (phase) {
    VoicePhase.IDLE -> Color(0xFF6C63FF) to Color(0xFFA78BFA)
    VoicePhase.LISTENING -> Color(0xFF22D3EE) to Color(0xFF3B82F6)
    VoicePhase.THINKING -> Color(0xFFA855F7) to Color(0xFFEC4899)
    VoicePhase.SPEAKING -> Color(0xFFFB923C) to Color(0xFFF472B6)
}

/**
 * The ball the user talks to (ADR-043). It breathes when idle, swells with the user's voice while
 * listening, swirls while thinking, and pulses while Anu speaks.
 */
@Composable
fun Orb(phase: VoicePhase, level: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (start, end) = colors(phase)
    val a by animateColorAsState(start, tween(600), label = "orb-a")
    val b by animateColorAsState(end, tween(600), label = "orb-b")
    val voice by animateFloatAsState(if (phase == VoicePhase.LISTENING) level else 0f, spring(dampingRatio = 0.55f, stiffness = 220f), label = "orb-level")

    val time = rememberInfiniteTransition(label = "orb")
    val breath by time.animateFloat(0f, 1f, infiniteRepeatable(tween(2_800), RepeatMode.Reverse), label = "breath")
    val spin by time.animateFloat(0f, 360f, infiniteRepeatable(tween(if (phase == VoicePhase.THINKING) 1_600 else 9_000, easing = LinearEasing)), label = "spin")
    val pulse by time.animateFloat(0f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "pulse")
    val ripple by time.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "ripple")

    val scale = when (phase) {
        VoicePhase.IDLE -> 0.95f + 0.04f * breath
        VoicePhase.LISTENING -> 1.0f + 0.2f * voice
        VoicePhase.THINKING -> 0.98f + 0.03f * sin(spin / 180f * PI.toFloat())
        VoicePhase.SPEAKING -> 1.0f + 0.07f * pulse
    }

    Canvas(
        modifier
            .semantics {
                contentDescription = "Talk to Anu"
                role = Role.Button
                stateDescription = when (phase) {
                    VoicePhase.IDLE -> "Ready"
                    VoicePhase.LISTENING -> "Listening"
                    VoicePhase.THINKING -> "Thinking"
                    VoicePhase.SPEAKING -> "Speaking"
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        val r = size.minDimension / 2f * 0.62f
        val c = center

        // Glow around the sphere.
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.42f), b.copy(alpha = 0.12f), Color.Transparent), c, r * 1.75f * scale), r * 1.75f * scale, c)

        // Ripples while listening, faster and wider the louder the voice.
        if (phase == VoicePhase.LISTENING) {
            for (i in 0 until 3) {
                val t = (ripple + i / 3f) % 1f
                drawCircle(a.copy(alpha = (1f - t) * (0.35f + 0.4f * voice)), r * scale * (1f + t * (0.45f + 0.5f * voice)), c, style = Stroke(width = 3f + 6f * voice))
            }
        }

        val radius = r * scale
        val sphere = Path().apply { addOval(Rect(c, radius)) }
        // The sphere: lit from the upper left, so it reads as round rather than flat.
        drawCircle(Brush.radialGradient(listOf(b, a, a.copy(alpha = 0.95f)), c + Offset(radius * 0.25f, radius * 0.3f), radius * 1.35f), radius, c)
        clipPath(sphere) {
            // Colour drifting inside, like liquid; it moves faster while thinking and with the voice.
            val drift = radius * (0.28f + 0.12f * voice + if (phase == VoicePhase.SPEAKING) 0.08f * pulse else 0f)
            listOf(0f to a.copy(red = (a.red + 0.25f).coerceAtMost(1f)), 120f to b, 240f to Color.White.copy(alpha = 0.6f)).forEach { (phaseShift, tint) ->
                val angle = (spin + phaseShift) / 180f * PI.toFloat()
                val at = c + Offset(cos(angle) * drift, sin(angle * 2f) * drift)
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0f)), at, radius * 0.75f), radius * 0.75f, at)
            }
        }
        // Light from the upper left, and a soft rim.
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0f)), c + Offset(-radius * 0.38f, -radius * 0.42f), radius * 0.7f), radius, c)
        drawCircle(Color.White.copy(alpha = 0.25f), radius, c, style = Stroke(width = 2f))
    }
}
