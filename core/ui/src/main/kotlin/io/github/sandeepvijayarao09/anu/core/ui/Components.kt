package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A card surface (DESIGN.md "Elevation & Depth"): a soft offset shadow in light mode, a hairline in dark
 * mode, never a glow.
 */
@Composable
fun Modifier.anuCard(shape: Shape = AnuShapes.md): Modifier {
    val colors = AnuTheme.colors
    return if (colors.isDark) {
        this.clip(shape).background(colors.surface).border(0.5.dp, Color.White.copy(alpha = 0.05f), shape)
    } else {
        this.shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(shape).background(colors.surface)
    }
}

/** A privacy dot: [AnuColors.local] when it stayed on this phone, [AnuColors.away] when it left. */
@Composable
fun PrivacyDot(away: Boolean, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    val colors = AnuTheme.colors
    Box(modifier.size(size).clip(CircleShape).background(if (away) colors.away else colors.local))
}

/**
 * The privacy indicator, top right of every page: "On device", or "N sent today" once anything has
 * left the phone. Tapping it opens the Privacy report.
 */
@Composable
fun PrivacyIndicator(sentToday: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = AnuTheme.colors
    val away = sentToday > 0
    val text = if (away) "$sentToday sent today" else "On device"
    Row(
        modifier
            .height(30.dp)
            .clip(AnuShapes.full)
            .background(colors.surface2)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open the privacy report", onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = if (away) "$sentToday items left your phone today" else "Everything stayed on this phone" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PrivacyDot(away)
        Text(text, style = AnuTheme.type.label, color = if (away) colors.awayText else colors.text)
    }
}

/**
 * A 50dp button: [secondary] is the quiet one. Declining uses the same size as approving. A
 * [destructive] secondary button, such as Delete, has error-red text.
 */
@Composable
fun AnuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = AnuTheme.colors
    val background = when {
        !enabled -> colors.surface2
        secondary -> colors.surface2
        else -> colors.primary
    }
    val content = when {
        !enabled -> colors.textFaint
        secondary && destructive -> colors.errorText
        secondary -> colors.text
        else -> colors.onPrimary
    }
    Box(
        modifier
            .heightIn(min = 50.dp)
            .clip(AnuShapes.md)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AnuTheme.type.headline, color = content, maxLines = 1)
    }
}

/** A tappable text in the tap tint, for secondary actions like "Undo" or "Type instead". */
@Composable
fun AnuTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = AnuTheme.colors.accent) {
    Text(
        text,
        modifier.clip(AnuShapes.sm).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        style = AnuTheme.type.bodyStrong,
        color = color,
    )
}

/** A page's large title and optional subtitle. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = AnuSpacing.xl)) {
        Text(title, Modifier.semantics { heading() }, style = AnuTheme.type.largeTitle, color = AnuTheme.colors.text)
        subtitle?.let { Text(it, Modifier.padding(top = 4.dp), style = AnuTheme.type.callout, color = AnuTheme.colors.textMuted) }
    }
}

/** A grouped list's section title. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 7.dp).semantics { heading() },
        style = AnuTheme.type.label.copy(fontWeight = FontWeight(600)),
        color = AnuTheme.colors.textMuted,
    )
}

/** A grouped list's card. Put [GroupRow]s in it, with [GroupDivider]s between them. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = AnuSpacing.xl).anuCard(), content = content)
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = AnuTheme.colors.separator)
}

/**
 * One row: a [title] with an optional [subtitle], then an optional trailing [value] (with a chevron when
 * the row opens something) or custom [trailing] content. A [leadingDot] before the value says where that
 * connection's data goes (DESIGN.md "Grouped lists"). An [icon] and a [titleColor] make action rows such
 * as "Add a server".
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leadingDot: Boolean? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    titleColor: Color? = null,
    subtitleColor: Color? = null,
    richSubtitle: AnnotatedString? = null,
    /** What TalkBack says for the value and its dot, when the dot's meaning isn't in the words. */
    valueDescription: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AnuTheme.colors
    val subtitleStyle = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, Modifier.size(20.dp), tint = if (enabled) titleColor ?: colors.text else colors.textFaint) }
        Column(Modifier.weight(1f)) {
            Text(title, style = AnuTheme.type.body, color = if (enabled) titleColor ?: colors.text else colors.textFaint)
            when {
                richSubtitle != null -> Text(richSubtitle, style = subtitleStyle, color = subtitleColor ?: colors.textMuted)
                subtitle != null -> Text(subtitle, style = subtitleStyle, color = subtitleColor ?: colors.textMuted)
            }
        }
        if (value != null || leadingDot != null) {
            Row(
                if (valueDescription != null) Modifier.clearAndSetSemantics { contentDescription = valueDescription } else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                leadingDot?.let { PrivacyDot(away = it) }
                value?.let { Text(it, style = AnuTheme.type.callout, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        trailing?.invoke(this)
        if (chevron) Icon(AnuIcons.ChevronRight, contentDescription = null, Modifier.size(16.dp), tint = colors.textFaint)
    }
}

/** A grouped list's footnote, under its card: what the group does with your data, in a sentence or two. */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 8.dp, bottom = 4.dp),
        style = AnuTheme.type.label.copy(fontWeight = FontWeight.Normal),
        color = AnuTheme.colors.textMuted,
    )
}

/** A result to acknowledge, such as "Key saved to the vault.", at the top of a page. */
@Composable
fun Notice(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AnuSpacing.xl, vertical = 6.dp)
            .anuCard()
            .padding(start = 16.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 12.dp), style = AnuTheme.type.callout, color = AnuTheme.colors.text)
        AnuTextButton("OK", onClick = onDismiss)
    }
}

/**
 * A switch: `accent` when on, with a thumb the same size in both states. Pass a null [onCheckedChange]
 * when the whole row is toggleable, so the row carries the state for TalkBack.
 */
@Composable
fun AnuSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = AnuTheme.colors
    val off = if (colors.isDark) Color(0xFF39393D) else Color(0xFFD1D1D6)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = {},
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = off,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.8f),
            disabledCheckedTrackColor = colors.accent.copy(alpha = 0.4f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.8f),
            disabledUncheckedTrackColor = off.copy(alpha = 0.5f),
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** A round 34dp button, such as Memory's "+", with a 48dp touch target. */
@Composable
fun AnuRoundButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = AnuTheme.colors
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(colors.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = if (enabled) colors.text else colors.textFaint)
        }
    }
}

/** A small tinted pill for one quick action in a row, such as Memory's "Confirm". */
@Composable
fun AnuPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AnuTheme.colors
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(AnuShapes.full)
            .background(colors.accent.copy(alpha = if (colors.isDark) 0.2f else 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AnuTheme.type.label.copy(fontWeight = FontWeight(600)), color = colors.accent)
    }
}

/** Where something goes, on its own line: an `away` dot when it leaves the phone, a `local` dot when it stays. */
@Composable
fun WhereLine(leaves: Boolean, text: String, modifier: Modifier = Modifier) {
    val colors = AnuTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        PrivacyDot(away = leaves)
        Text(text, style = AnuTheme.type.label, color = if (leaves) colors.awayText else colors.textMuted)
    }
}

/** Exact details in a sheet: labels on the left, values on the right, on the page color. */
@Composable
fun DetailPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(AnuShapes.md).background(AnuTheme.colors.background).padding(horizontal = 14.dp, vertical = 2.dp),
        content = content,
    )
}

/** One exact detail. [spoken] is what TalkBack says instead, such as a speed in words rather than "271/s". */
@Composable
fun DetailRow(label: String, value: String, first: Boolean = false, valueColor: Color? = null, spoken: String? = null) {
    val colors = AnuTheme.colors
    if (!first) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = "$label: $spoken" } else Modifier.semantics(mergeDescendants = true) {}),
    ) {
        Text(label, Modifier.width(84.dp), style = AnuTheme.type.callout, color = colors.textMuted)
        Text(value, style = AnuTheme.type.callout, color = valueColor ?: colors.text)
    }
}

/** A tab of the bottom bar. */
data class AnuTab(val label: String, val icon: ImageVector)

/**
 * The bottom tab bar (DESIGN.md): line icons with labels. The selected tab is in text color, the others
 * muted, with no indicator pill.
 */
@Composable
fun AnuTabBar(tabs: List<AnuTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = AnuTheme.colors
    Column(modifier.fillMaxWidth().background(colors.background)) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp), horizontalArrangement = Arrangement.SpaceAround) {
            tabs.forEachIndexed { index, tab ->
                val on = index == selected
                Column(
                    Modifier
                        .width(96.dp)
                        .clickable(role = Role.Tab) { onSelect(index) }
                        .semantics { this.selected = on }
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, contentDescription = null, Modifier.size(24.dp), tint = if (on) colors.text else colors.textMuted)
                    Spacer(Modifier.height(3.dp))
                    Text(tab.label, style = AnuTheme.type.tab, color = if (on) colors.text else colors.textMuted)
                }
            }
        }
    }
}
