package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A segmented control (DESIGN.md): a `full` track in `surface-2` whose selected segment is a `surface` pill with the
 * soft shadow, for a choice among a few peers. Each segment is a 48dp target, and TalkBack hears it as a radio button.
 */
@Composable
fun AnuSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = AnuTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(AnuShapes.full).background(colors.surface2).padding(4.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val chosen = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .then(if (chosen && !colors.isDark) Modifier.shadow(2.dp, AnuShapes.full, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f)) else Modifier)
                    .clip(AnuShapes.full)
                    .background(if (chosen) colors.surface else Color.Transparent)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    Modifier.padding(horizontal = 6.dp),
                    style = AnuTheme.type.label,
                    color = if (chosen) colors.text else colors.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A setting with a slider (DESIGN.md): its name and its exact value in mono on one line, the slider below in
 * `accent`. [value] is said by TalkBack as [shown] reads.
 */
@Composable
fun AnuSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val colors = AnuTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = AnuTheme.type.body, color = colors.text)
            Text(shown, style = AnuTheme.type.mono.copy(fontSize = AnuTheme.type.callout.fontSize, lineHeight = AnuTheme.type.callout.lineHeight), color = colors.textMuted)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = shown },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.separator,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}
