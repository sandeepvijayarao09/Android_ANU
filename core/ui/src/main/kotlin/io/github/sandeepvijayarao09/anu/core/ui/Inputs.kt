package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A text field (DESIGN.md: `sm` corners on `surface-2`). The [label] sits above it, inside the field's
 * own node, so TalkBack reads it with the text. A [secret] field is masked, uses a password keyboard
 * without suggestions, and should be kept in plain `remember`, never saved state.
 */
@Composable
fun AnuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = AnuTheme.colors
    val type = AnuTheme.type
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = type.body.copy(color = colors.text),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        decorationBox = { field ->
            Column {
                label?.let { Text(it, Modifier.padding(start = 4.dp, bottom = 6.dp), style = type.label, color = colors.textMuted) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(AnuShapes.sm)
                        .background(colors.surface2)
                        .border(1.dp, if (focused) colors.accent.copy(alpha = 0.6f) else Color.Transparent, AnuShapes.sm)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    // A multi-line field starts typing at the top, so its placeholder sits there too.
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = type.body, color = colors.textFaint)
                    field()
                }
            }
        },
    )
}

/** The 38dp search field (DESIGN.md `search-field`), with a clear button once something is typed. */
@Composable
fun AnuSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    val colors = AnuTheme.colors
    val type = AnuTheme.type
    val focus = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = type.body.copy(color = colors.text),
        singleLine = true,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(38.dp).clip(AnuShapes.sm).background(colors.surface2).padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AnuIcons.Search, contentDescription = null, Modifier.size(18.dp), tint = colors.textMuted)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = type.body, color = colors.textFaint, maxLines = 1)
                    field()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { onValueChange("") }.semantics { contentDescription = "Clear search" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AnuIcons.Close, contentDescription = null, Modifier.size(16.dp), tint = colors.textMuted)
                    }
                }
            }
        },
    )
}
