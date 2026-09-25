package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `bold, italic and code lose their markers and keep their style`() {
        val text = markdown("1. **No phone:** keep it *quiet* and run `adb`, _gently_")
        assertEquals("1. No phone: keep it quiet and run adb, gently", text.text)
        val styles = text.spanStyles.associate { text.text.substring(it.start, it.end) to it.item }
        assertEquals(FontWeight.Bold, styles.getValue("No phone:").fontWeight)
        assertEquals(FontStyle.Italic, styles.getValue("quiet").fontStyle)
        assertEquals(FontFamily.Monospace, styles.getValue("adb").fontFamily)
        assertEquals(FontStyle.Italic, styles.getValue("gently").fontStyle)
    }

    @Test
    fun `headings and bullets are formatted and line breaks are kept`() {
        val text = markdown("## Plan\n- First step\n  * nested")
        assertEquals("Plan\n• First step\n  • nested", text.text)
        assertTrue(text.spanStyles.any { text.text.substring(it.start, it.end) == "Plan" && it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `plain text and stray markers are left alone`() {
        assertEquals("2 * 3 = 6 and a lone ** marker", markdown("2 * 3 = 6 and a lone ** marker").text)
        assertEquals("see [the doc](https://example.com)", markdown("see [the doc](https://example.com)").text)
    }
}
