package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.FinishOpening
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sheet whose opening ends early opens again (ADR-086). On anu_api36 the keyboard hiding as an approval sheet
 * took focus moved the sheet's anchors in its last frames of opening: the opening ended off them, the sheet
 * stayed Hidden, and nothing opened it again (`ApprovalSheetImeTest` shows it on the device). An opening cut
 * short leaves the sheet in that same state, which these tests make on purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetOpeningTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var sheet: SheetState
    private lateinit var scope: CoroutineScope

    /** A sheet like the approval sheet ([required]: it can't be closed) or like `AnuSheet` (it can). */
    private fun showSheet(finish: Boolean, required: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AnuTheme(dark = false) {
                sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !required || it != SheetValue.Hidden })
                scope = rememberCoroutineScope()
                if (finish) FinishOpening(sheet, required)
                ModalBottomSheet(onDismissRequest = {}, sheetState = sheet) { Text("Remember this?", Modifier.padding(48.dp)) }
            }
        }
    }

    /** Takes the opening over and stops it partway, as the moved anchors did. */
    private fun cutOpeningShort() {
        var frames = 0
        while (!sheet.isAnimationRunning && frames++ < 20) compose.mainClock.advanceTimeByFrame()
        val opening = scope.launch { sheet.show() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        opening.cancel()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun `without FinishOpening, an opening that ends early leaves the sheet hidden`() {
        showSheet(finish = false, required = true)
        cutOpeningShort()
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse("the library leaves it hidden; if this fails, Material may have fixed it (ADR-086)", sheet.isVisible)
    }

    @Test
    fun `an approval sheet whose opening ends early opens again`() {
        showSheet(finish = true, required = true)
        cutOpeningShort()
        assertEquals(SheetValue.Expanded, sheet.currentValue)
        compose.onNodeWithText("Remember this?").assertExists()
    }

    @Test
    fun `a sheet that can be closed opens again too, and stays closed when closed`() {
        showSheet(finish = true, required = false)
        cutOpeningShort()
        assertEquals(SheetValue.Expanded, sheet.currentValue)

        scope.launch { sheet.hide() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals("closing is not an opening that ended early", SheetValue.Hidden, sheet.currentValue)
    }
}
