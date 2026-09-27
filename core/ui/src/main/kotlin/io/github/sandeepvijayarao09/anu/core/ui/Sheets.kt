package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A sheet (DESIGN.md: `xl` corners, grabber, scrim) for details and short forms. [content] gets `close`,
 * which slides the sheet away before [onDismiss] runs. It scrolls, and stays above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnuSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val colors = AnuTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, shape = AnuShapes.sheet, containerColor = colors.surface, scrimColor = colors.scrim) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(start = AnuSpacing.xl, end = AnuSpacing.xl, bottom = AnuSpacing.lg),
        ) {
            content(close)
        }
    }
}

/** A sheet's heading in title style, with an optional muted line above it. */
@Composable
fun SheetTitle(title: String, modifier: Modifier = Modifier, overline: String? = null) {
    Column(modifier) {
        overline?.let { Text(it, style = AnuTheme.type.label, color = AnuTheme.colors.textMuted) }
        Text(title, Modifier.semantics { heading() }, style = AnuTheme.type.title, color = AnuTheme.colors.text)
    }
}

/** A confirmation for something that can't be undone in one tap, such as deleting. [destructive] makes the confirm red. */
@Composable
fun AnuDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = AnuTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { AnuTextButton(confirm, onClick = onConfirm, color = if (destructive) colors.errorText else colors.accent) },
        dismissButton = { AnuTextButton(dismiss, onClick = onDismiss) },
        title = { Text(title, style = AnuTheme.type.title, color = colors.text) },
        text = { Text(text, style = AnuTheme.type.callout, color = colors.textMuted) },
        containerColor = colors.surface,
        shape = AnuShapes.xl,
    )
}

/** A quiet line of text inside a sheet, such as "The key goes into Anu's vault." */
@Composable
fun SheetNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        style = AnuTheme.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
        color = AnuTheme.colors.textMuted,
    )
}
