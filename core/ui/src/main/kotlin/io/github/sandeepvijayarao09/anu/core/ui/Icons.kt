package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Anu's line icons (DESIGN.md): 24dp, 1.7 stroke, round caps. Tint them with Icon's tint; the path color
 * here is only a placeholder.
 */
object AnuIcons {
    val Talk = line("M19.5 12a7.5 7.5 0 1 1-15 0a7.5 7.5 0 1 1 15 0Z", "M14.5 12a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0Z")
    val Memory = line("M6 4.5h10.5a1.5 1.5 0 0 1 1.5 1.5v13.5H7.5A1.5 1.5 0 0 1 6 18V4.5Z", "M6 18a1.5 1.5 0 0 1 1.5-1.5H18", "M9.5 8.5h5")
    val Settings = line("M4 7h10M18 7h2M4 17h4M12 17h8", "M18 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0Z", "M12 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0Z")
    val Calendar = line("M6 5.5h12a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7.5a2 2 0 0 1 2-2Z", "M4 10h16M8.5 3.5v4M15.5 3.5v4")
    val Mail = line("M5.5 5.5h13a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2Z", "M4 7l8 6l8-6")
    val Message = line("M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H10l-4 3v-3H5A1.5 1.5 0 0 1 3.5 16V7A1.5 1.5 0 0 1 5 5.5Z")
    val Person = line("M15.5 8a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0Z", "M5 20c.8-3.4 3.6-5.5 7-5.5s6.2 2.1 7 5.5")
    val Bag = line("M5.5 8h13l-1 11.5a1.5 1.5 0 0 1-1.5 1.4H8a1.5 1.5 0 0 1-1.5-1.4L5.5 8Z", "M9 10V7a3 3 0 0 1 6 0v3")
    val Service = line("M9 7.5H7a4.5 4.5 0 0 0 0 9h2M15 7.5h2a4.5 4.5 0 0 1 0 9h-2M8.5 12h7")
    val Note = line("M6.5 3.5h8l3.5 3.5v12a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5Z", "M14.5 3.5V7H18M8.5 12h7M8.5 15.5h5")
    val Sparkle = line("M12 3.5c.6 3.9 2.6 5.9 6.5 6.5c-3.9.6-5.9 2.6-6.5 6.5c-.6-3.9-2.6-5.9-6.5-6.5c3.9-.6 5.9-2.6 6.5-6.5Z")
    val Plus = line("M12 5v14M5 12h14")
    val Photo = line("M4 5.5h16v13H4z", "M4 15.5l4.5-4.5 4 4 2.5-2.5 5 5", "M15.5 9h.01")
    val Search = line("M16.5 10.5a6 6 0 1 1-12 0a6 6 0 1 1 12 0Z", "M15 15l4.5 4.5")
    val ChevronRight = line("M9.5 6l6 6l-6 6")
    val ChevronDown = line("M6 9.5l6 6l6-6")
    val ChevronLeft = line("M14.5 6l-6 6l6 6")
    val Close = line("M6.5 6.5l11 11M17.5 6.5l-11 11")
    val ArrowUp = line("M12 19V5.5M6.5 11l5.5-5.5l5.5 5.5")
    val Mic = fill("M12 15a3 3 0 0 0 3-3V6a3 3 0 1 0-6 0v6a3 3 0 0 0 3 3Zm5-3a1 1 0 1 1 2 0a7 7 0 0 1-6 6.93V21h-2v-2.07A7 7 0 0 1 5 12a1 1 0 1 1 2 0a5 5 0 0 0 10 0Z")
    val Stop = fill("M7.5 6h9a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 16.5v-9A1.5 1.5 0 0 1 7.5 6Z")

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
        .addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        .build()
}
