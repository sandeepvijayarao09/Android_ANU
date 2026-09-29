package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuDialog
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.DetailPanel
import io.github.sandeepvijayarao09.anu.core.ui.DetailRow
import io.github.sandeepvijayarao09.anu.core.ui.SheetNote
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine

/**
 * An installed model's sheet (design review 2C, D12; ADR-073): where it runs, its build and space, how fast it has
 * been for the user and in Test speed, and whether the phone is warm. Test speed is the primary action, and Delete
 * is below it.
 */
@Composable
internal fun ModelSheet(
    row: ModelRow,
    sheet: ModelSheet,
    now: Long,
    onTest: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AnuTheme.colors
    val name = modelName(row.info.id)
    var confirmDelete by remember { mutableStateOf(false) }
    AnuSheet(onDismiss) { close ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(AnuShapes.sm).background(colors.surface2), contentAlignment = Alignment.Center) {
                Icon(AnuIcons.Sparkle, contentDescription = null, Modifier.size(22.dp), tint = colors.text)
            }
            SheetTitle(name)
        }
        WhereLine(leaves = false, text = "Stays on this phone", modifier = Modifier.padding(top = 12.dp))
        DetailPanel(Modifier.padding(top = 16.dp)) {
            DetailRow("Tier", if (row.info.id == BrainId.BASE) "Base · faster" else "Pro · harder requests", first = true)
            DetailRow("Build", buildLine(row.info.variant))
            DetailRow("Space", modelSpace(row))
            val realUse = sheet.realUse
            when {
                realUse != null -> DetailRow(
                    "Your answers",
                    "${speedLine(realUse)} · from ${answers(realUse.answers)}",
                    spoken = "${speedWords(realUse)}, from your last ${answers(realUse.answers)}",
                )
                sheet.realUseLoaded -> DetailRow("Your answers", "No answers yet")
            }
            val lastTest = sheet.lastTest
            if (lastTest != null) {
                val warmNote = if (lastTest.warm) " · phone was warm" else ""
                DetailRow(
                    "Last test",
                    "${speedLine(lastTest)} · ${ago(lastTest.atMillis, now)}$warmNote",
                    spoken = "${speedWords(lastTest)}, ${ago(lastTest.atMillis, now)}$warmNote",
                )
            } else {
                DetailRow("Last test", "Not tested yet")
            }
            DetailRow("Phone", if (sheet.warm) "Warm" else "Cool")
        }
        TestProgress(sheet, name)
        if (sheet.warm && sheet.test !is TestState.Running) SheetNote("The phone is warm, so a test now won't compare with one on a cool phone.")
        SheetNote("Testing sets your chat aside, so its next answer reads the chat again.")
        Spacer(Modifier.height(18.dp))
        if (sheet.test is TestState.Running) {
            AnuButton("Stop test", onClick = onStop, Modifier.fillMaxWidth())
        } else {
            AnuButton("Test speed", onClick = onTest, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        // A model is gigabytes to download again, so deleting one is never a single tap.
        AnuButton("Delete", onClick = { confirmDelete = true }, Modifier.fillMaxWidth(), secondary = true, destructive = true)
        if (confirmDelete) {
            AnuDialog(
                title = "Delete $name?",
                text = "This frees ${gigabytes(row.spaceUsed)}. Downloading it again takes a while on Wi-Fi.",
                confirm = "Delete",
                onConfirm = { confirmDelete = false; onDelete(); close() },
                dismiss = "Keep",
                onDismiss = { confirmDelete = false },
                destructive = true,
            )
        }
    }
}

/** The test's step while it runs, what a stopped test measured, or why it failed. TalkBack hears each change. */
@Composable
private fun TestProgress(sheet: ModelSheet, name: String) {
    val colors = AnuTheme.colors
    val style = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal)
    val live = Modifier.padding(top = 12.dp, start = 2.dp).semantics { liveRegion = LiveRegionMode.Polite }
    when (val test = sheet.test) {
        is TestState.Running -> Text(testStepLine(test.step, name), live, style = style, color = colors.textMuted)
        is TestState.Stopped -> Text("Stopped" + (test.speed?.let { partial(it) } ?: ""), live, style = style, color = colors.textMuted)
        is TestState.Failed -> Text("Couldn't test: ${test.reason}", live, style = style, color = colors.errorText)
        TestState.Idle -> Unit
    }
}

private fun partial(speed: Speed): String = speedLine(speed).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""

private fun answers(count: Int) = if (count == 1) "1 answer" else "$count answers"
