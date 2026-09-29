package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.PromptLab
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The prompt lab (G7, ADR-080), with a fake in place of the phone's models. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabTest {

    @get:Rule val compose = createComposeRule()

    /** Answers with [events], or waits forever when [hang]; records what it was asked. */
    private class FakeLab(
        private val installed: List<BrainId> = listOf(BrainId.BASE, BrainId.PRO),
        private val events: List<BrainEvent> = emptyList(),
        private val hang: Boolean = false,
        private val failure: Exception? = null,
    ) : PromptLab {
        val asked = mutableListOf<Pair<BrainId, BrainRequest>>()

        override fun models() = installed

        override fun run(id: BrainId, request: BrainRequest): Flow<BrainEvent> = flow {
            asked += id to request
            failure?.let { throw it }
            events.forEach { emit(it) }
            if (hang) awaitCancellation()
        }
    }

    private val answer = listOf(
        BrainEvent.TextDelta("I reasoned.", thought = true),
        BrainEvent.TextDelta("A tall "),
        BrainEvent.TextDelta("tower with a light."),
        BrainEvent.Usage(contextTokens = 40, inputTokens = 20, outputTokens = 8, readMs = 500, writeMs = 1_000, counted = true),
        BrainEvent.Finished(StopReason.COMPLETE),
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `it starts on Pro with Gemma's defaults, and a prompt is needed to run`() {
        val lab = LabViewModel(FakeLab())
        val state = lab.state.value
        assertEquals(BrainId.PRO, state.model)
        assertEquals(1.0f, state.temperature)
        assertEquals(64, state.topK)
        assertEquals(0.95f, state.topP)
        assertFalse(state.canRun)
        lab.setPrompt("What is a lighthouse?")
        assertTrue(lab.state.value.canRun)
    }

    @Test
    fun `a run streams the answer, keeps the reasoning apart, and ends with its speed`() {
        val fake = FakeLab(events = answer)
        val lab = LabViewModel(fake, nanoTime = { 0L })
        lab.chooseModel(BrainId.BASE)
        lab.setPrompt("What is a lighthouse?")
        lab.setThinking(true)
        lab.run()
        val state = lab.state.value
        assertEquals("A tall tower with a light.", state.output)
        assertEquals("I reasoned.", state.thought)
        assertFalse(state.running)
        assertEquals("First word 0.0 s · reads 40/s · writes 8.0/s", state.speed)
        val (id, request) = fake.asked.single()
        assertEquals(BrainId.BASE, id)
        assertTrue(request.thinking)
        assertTrue("the lab sends no tools", request.tools.isEmpty())
        assertNull("nor memory", request.memory)
    }

    @Test
    fun `a template writes an instruction the user can still change, and temperature 0 is greedy`() {
        val fake = FakeLab(events = answer)
        val lab = LabViewModel(fake)
        lab.chooseTemplate(LabTemplate.REWRITE)
        assertEquals("Formal", lab.state.value.option)
        assertTrue(lab.state.value.instruction.startsWith("Rewrite the user's text to be formal."))
        lab.chooseOption("Friendly")
        assertTrue(lab.state.value.instruction.startsWith("Rewrite the user's text to be friendly."))
        lab.setInstruction("Rewrite it as a haiku.")
        lab.setTemperature(0f)
        lab.setPrompt("The meeting moved to Friday.")
        lab.run()
        val request = fake.asked.single().second
        assertEquals("Rewrite it as a haiku.", request.system)
        assertEquals(Sampling.Precise, request.sampling)
    }

    @Test
    fun `Stop keeps what was written and marks it, and a failure says so in words`() {
        val lab = LabViewModel(FakeLab(events = listOf(BrainEvent.TextDelta("A tall")), hang = true))
        lab.setPrompt("What is a lighthouse?")
        lab.run()
        assertTrue(lab.state.value.running)
        lab.stop()
        assertEquals("A tall", lab.state.value.output)
        assertTrue(lab.state.value.stopped)
        assertFalse(lab.state.value.running)

        val broken = LabViewModel(FakeLab(failure = IllegalStateException("out of memory")))
        broken.setPrompt("Hi")
        broken.run()
        assertEquals("The model couldn't answer: out of memory", broken.state.value.error)
    }

    @Test
    fun `with no model the page offers Models, and with one Run waits for a prompt`() {
        var models = 0
        compose.setContent {
            AnuTheme(dark = false) { LabPage(LabViewModel(FakeLab(installed = emptyList())), 0, {}, {}, onOpenModels = { models++ }) }
        }
        compose.onNodeWithText("Download a model to use the lab").performClick()
        assertEquals(1, models)
    }

    @Test
    fun `Run is enabled once there's a prompt, and the answer shows with Copy`() {
        val lab = LabViewModel(FakeLab(events = answer), nanoTime = { 0L })
        compose.setContent { AnuTheme(dark = false) { LabPage(lab, 0, {}, {}, onOpenModels = {}) } }
        val page = compose.onNode(hasScrollToNodeAction())
        page.performScrollToNode(hasText("Run"))
        compose.onNodeWithText("Run").assertIsNotEnabled()
        lab.setPrompt("What is a lighthouse?")
        compose.onNodeWithText("Run").assertIsEnabled().performClick()
        page.performScrollToNode(hasText("Copy"))
        compose.onNodeWithText("A tall tower with a light.").assertExists()
        compose.onNodeWithText("Copy").assertExists()
    }
}
