package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Anu's colors (DESIGN.md). Neutrals do almost everything. Green and orange mean only where data is:
 * [local] stayed on this phone, [away] left it. [accent] is only for things you can tap.
 */
@Immutable
data class AnuColors(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val separator: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val local: Color,
    val away: Color,
    /** Orange text: [away] itself is too light for text on light surfaces. */
    val awayText: Color,
    val error: Color,
    val scrim: Color,
    /** The assistant card, the one translucent surface. */
    val material: Color,
    val isDark: Boolean,
)

val LightColors = AnuColors(
    background = Color(0xFFF5F5F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEDEDF0), separator = Color(0xFFDCDCE0),
    text = Color(0xFF1D1D1F), textMuted = Color(0xFF6E6E73), textFaint = Color(0xFFA1A1A6),
    primary = Color(0xFF1D1D1F), onPrimary = Color(0xFFFFFFFF), accent = Color(0xFF1463FF),
    local = Color(0xFF1E9E5A), away = Color(0xFFD96A00), awayText = Color(0xFFB35600), error = Color(0xFFE5383B),
    scrim = Color(0x47000000), material = Color(0xC7FAFAFC), isDark = false,
)

val DarkColors = AnuColors(
    background = Color(0xFF000000), surface = Color(0xFF1C1C1E), surface2 = Color(0xFF2C2C2E), separator = Color(0xFF38383A),
    text = Color(0xFFF5F5F7), textMuted = Color(0xFF98989D), textFaint = Color(0xFF636366),
    primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF000000), accent = Color(0xFF4D8DFF),
    local = Color(0xFF30D158), away = Color(0xFFFF9F0A), awayText = Color(0xFFFF9F0A), error = Color(0xFFFF453A),
    scrim = Color(0x80000000), material = Color(0xB81E1E20), isDark = true,
)

/** The type scale (DESIGN.md): one variable family with optical sizes, plus mono for exact values. */
@Immutable
data class AnuTypography(
    val largeTitle: TextStyle,
    val title: TextStyle,
    val liveSpeech: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val callout: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
    val wordmark: TextStyle,
    val tab: TextStyle,
)

/**
 * Google Sans Flex, bundled in res/font (no run-time font requests). Each style sets the optical size to
 * its own size, like SF Pro, and carries every weight the UI uses, so `style.copy(fontWeight = …)` picks
 * the matching instance. In dark mode the grade rises a little, so light-on-black text doesn't look thin,
 * without changing widths.
 */
private fun flex(size: TextUnit, grade: Int): FontFamily = FontFamily(
    WEIGHTS.map { weight ->
        Font(
            R.font.google_sans_flex,
            FontWeight(weight),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.opticalSizing(size),
                FontVariation.grade(grade),
            ),
        )
    },
)

private val WEIGHTS = listOf(400, 500, 550, 600, 650, 700)

private val mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))))

fun anuTypography(dark: Boolean): AnuTypography {
    val grade = if (dark) 40 else 0
    fun style(weight: Int, size: Float, line: Float, tracking: Float = 0f) = TextStyle(
        fontFamily = flex(size.sp, grade),
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
    )
    return AnuTypography(
        largeTitle = style(700, 34f, 41f, -0.025f),
        title = style(600, 22f, 28f, -0.015f),
        liveSpeech = style(550, 26f, 33f, -0.015f),
        headline = style(600, 17f, 22f),
        body = style(400, 16f, 23f),
        bodyStrong = style(550, 16f, 23f),
        callout = style(400, 15f, 20f),
        label = style(550, 13f, 18f, 0.01f),
        caption = style(400, 12f, 16f),
        mono = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        wordmark = style(650, 20f, 24f, -0.02f),
        tab = style(550, 11f, 13f),
    )
}

object AnuShapes {
    val sm = RoundedCornerShape(10.dp)
    val md = RoundedCornerShape(14.dp)
    val lg = RoundedCornerShape(18.dp)
    val xl = RoundedCornerShape(28.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val full = CircleShape
}

object AnuSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
}

private val LocalAnuColors = staticCompositionLocalOf { LightColors }
private val LocalAnuTypography = staticCompositionLocalOf { anuTypography(dark = false) }

/**
 * Anu's theme. Material 3 stays underneath for behavior and accessibility, mapped onto Anu's tokens:
 * its primary is the tap tint, surfaces are Anu's neutrals, and there is no tonal tint or wallpaper color.
 */
@Composable
fun AnuTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val type = remember(dark) { anuTypography(dark) }
    CompositionLocalProvider(LocalAnuColors provides colors, LocalAnuTypography provides type) {
        MaterialTheme(colorScheme = materialColors(colors), typography = materialTypography(type), shapes = materialShapes, content = content)
    }
}

object AnuTheme {
    val colors: AnuColors
        @Composable get() = LocalAnuColors.current
    val type: AnuTypography
        @Composable get() = LocalAnuTypography.current
}

private fun materialColors(c: AnuColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent, onPrimary = Color.Black, primaryContainer = c.surface2, onPrimaryContainer = c.text,
        secondary = c.text, onSecondary = c.background, secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        tertiary = c.accent, onTertiary = Color.Black, background = c.background, onBackground = c.text,
        surface = c.background, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.textMuted,
        surfaceTint = Color.Transparent, inverseSurface = c.text, inverseOnSurface = c.background,
        error = c.error, onError = Color.Black, outline = c.separator, outlineVariant = c.separator, scrim = Color.Black,
        surfaceBright = c.surface2, surfaceDim = c.background, surfaceContainerLowest = c.background, surfaceContainerLow = c.surface,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface2,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = Color.White, primaryContainer = c.surface2, onPrimaryContainer = c.text,
        secondary = c.text, onSecondary = c.background, secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        tertiary = c.accent, onTertiary = Color.White, background = c.background, onBackground = c.text,
        surface = c.background, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.textMuted,
        surfaceTint = Color.Transparent, inverseSurface = c.text, inverseOnSurface = c.background,
        error = c.error, onError = Color.White, outline = c.separator, outlineVariant = c.separator, scrim = Color.Black,
        surfaceBright = c.surface, surfaceDim = c.background, surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface2,
    )
}

private fun materialTypography(t: AnuTypography) = Typography(
    displaySmall = t.largeTitle,
    headlineMedium = t.largeTitle,
    headlineSmall = t.title,
    titleLarge = t.title,
    titleMedium = t.headline,
    titleSmall = t.label,
    bodyLarge = t.body,
    bodyMedium = t.callout,
    bodySmall = t.label.copy(fontWeight = FontWeight.Normal),
    labelLarge = t.bodyStrong.copy(fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = t.label,
    labelSmall = t.caption,
)

private val materialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = AnuShapes.sm,
    medium = AnuShapes.md,
    large = AnuShapes.lg,
    extraLarge = AnuShapes.xl,
)
