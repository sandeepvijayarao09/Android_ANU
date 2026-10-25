package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.SheetNote
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
import kotlinx.coroutines.delay

/** Where teaching "Hey Anu" is (ADR-121). */
private sealed interface Teaching {
    /** The voice is already learned; the sheet offers to record it again or forget it. */
    data object Learned : Teaching

    data object Recording : Teaching

    /** The last sample wasn't kept, and why. */
    data class Again(val reason: String) : Teaching

    /** Every sample is in. */
    data object Ready : Teaching
}

/**
 * Teaching "Hey Anu" the user's voice (ADR-121). It records [WakeVoiceSetup.samples] samples one after another, each
 * ending when the user stops talking; a dot fills for each one kept, and the one being recorded follows their voice. A
 * sample that isn't kept says why and waits for Try again, so the microphone never loops on its own. Done keeps the
 * voice; closing the sheet keeps nothing.
 */
@Composable
internal fun WakeVoiceSheet(
    learned: Boolean,
    setup: () -> WakeVoiceSetup?,
    onDone: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AnuTheme.colors
    val session = remember { setup() } ?: return
    DisposableEffect(session) { onDispose { session.close() } }
    var state by remember { mutableStateOf(if (learned) Teaching.Learned else Teaching.Recording) }
    var kept by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state, attempt) {
        if (state != Teaching.Recording) return@LaunchedEffect
        session.record().collect { event ->
            when (event) {
                is WakeSampleEvent.Level -> level = event.value
                is WakeSampleEvent.Kept -> {
                    kept = event.count
                    level = 0f
                    if (kept >= session.samples) {
                        state = Teaching.Ready
                    } else {
                        // A breath between samples, so the next one doesn't start mid-word.
                        delay(500)
                        attempt++
                    }
                }
                is WakeSampleEvent.Again -> {
                    level = 0f
                    state = Teaching.Again(event.reason)
                }
            }
        }
    }
    AnuSheet(onDismiss) { close ->
        SheetTitle(if (state == Teaching.Learned) "Your voice" else "Teach Anu your voice")
        WhereLine(leaves = false, text = "Stays on this phone", modifier = Modifier.padding(top = 12.dp))
        Column(
            Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (state) {
                    Teaching.Learned -> "Anu knows your “Hey Anu”"
                    Teaching.Ready -> "That's all"
                    else -> "Say “Hey Anu”"
                },
                style = AnuTheme.type.title,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            if (state != Teaching.Learned) {
                Spacer(Modifier.height(20.dp))
                SampleDots(kept, session.samples, recording = state == Teaching.Recording, level)
            }
            val line = when (val now = state) {
                Teaching.Recording -> "Sample ${minOf(kept + 1, session.samples)} of ${session.samples}"
                is Teaching.Again -> now.reason
                Teaching.Ready -> "${session.samples} samples, kept as a summary of how you sound"
                Teaching.Learned -> "It listens for your samples while the screen is on"
            }
            Text(
                line,
                Modifier.padding(top = 14.dp).heightIn(min = 36.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        SheetNote("Say it the way you usually would. Anu keeps numbers that describe how it sounds, encrypted on this phone, never the recording.")
        Spacer(Modifier.height(18.dp))
        when (state) {
            Teaching.Ready -> AnuButton("Done", onClick = { session.save(); onDone(); close() }, Modifier.fillMaxWidth())
            is Teaching.Again -> AnuButton("Try again", onClick = { state = Teaching.Recording; attempt++ }, Modifier.fillMaxWidth())
            Teaching.Learned -> {
                AnuButton("Record again", onClick = { kept = 0; state = Teaching.Recording }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                AnuButton("Forget my voice", onClick = { onForget(); close() }, Modifier.fillMaxWidth(), secondary = true, destructive = true)
            }
            Teaching.Recording -> AnuButton("Cancel", onClick = close, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

/** One dot per sample: filled once kept; the one being recorded swells with the user's voice. */
@Composable
private fun SampleDots(kept: Int, total: Int, recording: Boolean, level: Float) {
    val colors = AnuTheme.colors
    val swell by animateFloatAsState(if (recording) 1f + level * 0.9f else 1f, spring(dampingRatio = 1f, stiffness = 400f), label = "level")
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$kept of $total samples kept" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val current = recording && index == kept
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(12.dp)
                        .graphicsLayer {
                            scaleX = if (current) swell else 1f
                            scaleY = if (current) swell else 1f
                        }
                        .clip(CircleShape)
                        .background(if (index < kept) colors.text else colors.surface2)
                        .then(if (current) Modifier.border(1.5.dp, colors.text, CircleShape) else Modifier),
                )
            }
        }
    }
}
