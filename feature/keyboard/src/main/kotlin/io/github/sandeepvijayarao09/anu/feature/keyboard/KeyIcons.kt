package io.github.sandeepvijayarao09.anu.feature.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The keyboard's glyphs, drawn as Anu's line icons are (DESIGN.md): 24dp, 1.7 stroke, round caps. */
internal object KeyIcons {
    private const val SHIFT = "M12 4.5l7.5 7.5H15.5v7h-7v-7H4.5Z"
    val Shift = line(SHIFT)
    val ShiftOn = fill(SHIFT)
    val ShiftLocked = fill("$SHIFT M8.5 21h7v-0.01h-7Z")
    val Backspace = line("M9 5.5h10.5a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H9L3.5 12Z", "M12 9.5l5 5M17 9.5l-5 5")
    val Globe = line(
        "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0Z",
        "M3.5 12h17",
        "M12 3.5c2.3 2.3 3.5 5.2 3.5 8.5s-1.2 6.2-3.5 8.5c-2.3-2.3-3.5-5.2-3.5-8.5s1.2-6.2 3.5-8.5Z",
    )
    val Return = line("M19 6v6.5a1.5 1.5 0 0 1-1.5 1.5H6", "M9.5 10.5L6 14l3.5 3.5")
    val Lock = line("M7 10.5h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1Z", "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")

    private fun line(vararg paths: String): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        .build()

    private fun fill(d: String): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(d),
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}
