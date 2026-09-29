package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelInfo
import io.github.sandeepvijayarao09.anu.core.model.ModelSpeed
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.model.OnDeviceModels
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.model.TestStep
import io.github.sandeepvijayarao09.anu.core.model.TestUpdate
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A model's sheet and Test speed (design review 2C and D12, ADR-073), with a fake in place of the phone. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelSheetTest {

    @get:Rule val compose = createComposeRule()

    private class FakeModels : OnDeviceModels {
        override fun info(id: BrainId) = ModelInfo(id, "gemma-4-E4B-it.litertlm", 3_659_530_240, "CPU")
        override fun isInstalled(id: BrainId) = true
        override fun spaceUsed(id: BrainId) = 5_900_000_000
        override fun state(id: BrainId): Flow<ModelState> = flowOf(ModelState.Ready)
        override fun download(id: BrainId) = Unit
        override fun delete(id: BrainId) = Unit
    }

    private class FakeSpeeds(var run: (BrainId) -> Flow<TestUpdate>) : ModelSpeed {
        var warm = false
        override suspend fun realUse(id: BrainId) = Speed(firstWordMs = 3_400, readPerSecond = 120.0, writePerSecond = 6.1, answers = 12)
        override fun lastTest(id: BrainId): Speed? = null
        override fun test(id: BrainId) = run(id)
        override fun warm() = warm
    }

    private class FakeCloudKey : CloudKeySettings {
        override val hasKey = kotlinx.coroutines.flow.MutableStateFlow(false)
        override suspend fun save(apiKey: String) = Unit
        override suspend fun remove() = Unit
    }

    private val done = Speed(firstWordMs = 1_200, readPerSecond = 271.0, writePerSecond = 5.3, counted = true, atMillis = 1_000)

    private fun viewModel(speeds: FakeSpeeds) = SettingsViewModel(FakeModels(), FakeCloudKey(), flowOf(emptyList()), speeds = speeds)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `Test speed goes step by step and its result becomes the last test`() {
        val speeds = FakeSpeeds { flowOf(TestUpdate(TestStep.WAITING), TestUpdate(TestStep.READING), TestUpdate(TestStep.DONE, done)) }
        val model = viewModel(speeds)
        model.openModel(BrainId.PRO)
        assertEquals(12, model.modelSheet.value!!.realUse!!.answers)
        model.testSpeed()
        val sheet = model.modelSheet.value!!
        assertEquals(TestState.Idle, sheet.test)
        assertEquals(done, sheet.lastTest)
    }

    @Test
    fun `stopping a test keeps what it measured, marked stopped`() {
        val partial = Speed(firstWordMs = 1_100, readPerSecond = null, writePerSecond = 4.0)
        val speeds = FakeSpeeds { flow { emit(TestUpdate(TestStep.WRITING, partial)); awaitCancellation() } }
        val model = viewModel(speeds)
        model.openModel(BrainId.PRO)
        model.testSpeed()
        assertEquals(TestState.Running(TestStep.WRITING, partial), model.modelSheet.value!!.test)
        model.stopTest()
        assertEquals(TestState.Stopped(partial), model.modelSheet.value!!.test)
    }

    @Test
    fun `a failed test says why`() {
        val speeds = FakeSpeeds { flow { throw IllegalStateException("out of memory") } }
        val model = viewModel(speeds)
        model.openModel(BrainId.PRO)
        model.testSpeed()
        assertEquals(TestState.Failed("out of memory"), model.modelSheet.value!!.test)
    }

    @Test
    fun `the sheet shows the build, real use, the last test and the phone, and its buttons work`() {
        var tested = 0
        var deleted = 0
        val row = ModelRow(FakeModels().info(BrainId.PRO), ModelState.Ready, spaceUsed = 5_900_000_000)
        val sheet = ModelSheet(
            BrainId.PRO,
            realUse = Speed(firstWordMs = 3_400, readPerSecond = 120.0, writePerSecond = 6.1, answers = 12),
            realUseLoaded = true,
            lastTest = done.copy(atMillis = 0, warm = true),
            warm = true,
        )
        compose.setContent {
            AnuTheme(dark = false) { ModelSheet(row, sheet, now = 120_000, onTest = { tested++ }, onStop = {}, onDelete = { deleted++ }, onDismiss = {}) }
        }
        compose.onNodeWithText("Gemma 4 E4B").assertExists()
        compose.onNodeWithText("Stays on this phone").assertExists()
        compose.onNodeWithText("Runs on the CPU").assertExists()
        compose.onNodeWithContentDescription(
            "Your answers: first word 3.4 seconds, reads about 120 tokens a second, writes about 6.1 tokens a second, from your last 12 answers",
        ).assertExists()
        compose.onNodeWithContentDescription(
            "Last test: first word 1.2 seconds, reads 271 tokens a second, writes 5.3 tokens a second, 2 min ago · phone was warm",
        ).assertExists()
        compose.onNodeWithText("Warm").assertExists()
        compose.onNodeWithText("The phone is warm, so a test now won't compare with one on a cool phone.").assertExists()
        compose.onNodeWithText("Test speed").performScrollTo().performClick()
        assertEquals(1, tested)
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete Gemma 4 E4B?").assertExists()
        assertTrue("Delete asks first", deleted == 0)
    }
}
