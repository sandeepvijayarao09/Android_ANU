package io.github.sandeepvijayarao09.anu.feature.writing

import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WritingViewModelTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
    private var device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO, Tier.CLOUD))
    private val brains = mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)
    private lateinit var writing: WritingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        writing = WritingViewModel(Cascade(Router(), BrainProvider { brains[it] }), device = { device }, memory = { "- manager: Maya Chen" })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = writing.state.value

    @Test
    fun `a task streams its result, cleaned, ready to replace`() {
        writing.start("I has a cat.", readOnly = false)
        base.enqueue(Step.Reply(listOf("Sure! Here's the corrected text:\n\n", "I have a cat.")))
        writing.run(WritingAction.PROOFREAD)
        assertEquals("I have a cat.", state.result)
        assertTrue(state.done && !state.busy && !state.readOnly)
        assertEquals(Tier.BASE, state.tier)
        val request = base.requests.single()
        assertTrue((request.transcript.single() as ChatMessage.User).text.endsWith("<text>\nI has a cat.\n</text>"))
        assertEquals(Sampling.Precise, request.sampling)
        assertNull("proofreading doesn't need memory", request.memory)
        assertEquals("writing", request.purpose)
    }

    @Test
    fun `tasks in the user's voice carry memory, and others don't`() {
        writing.start("Thanks for the update, will review tomorrow.", readOnly = false)
        base.enqueue(Step.Reply("Thank you for the update. I'll review it tomorrow."), Step.Reply("Thanks, will review tomorrow."))
        writing.run(WritingAction.FORMAL)
        writing.run(WritingAction.SHORTER)
        assertEquals(listOf("- manager: Maya Chen", null), base.requests.map { it.memory })
    }

    @Test
    fun `retry with Pro pins the Pro tier`() {
        writing.start("pls send the file asap", readOnly = true)
        base.enqueue(Step.Reply("Please send the file soon."))
        pro.enqueue(Step.Reply("Could you please send the file as soon as possible?"))
        writing.run(WritingAction.FORMAL)
        writing.retryWithPro()
        assertEquals(Tier.PRO, state.tier)
        assertEquals("Could you please send the file as soon as possible?", state.result)
        assertTrue(state.readOnly)
    }

    @Test
    fun `an empty result is repaired once, then escalated`() {
        writing.start("hello wrld", readOnly = false)
        base.enqueue(Step.Reply(""), Step.Reply("   "))
        pro.enqueue(Step.Reply("Hello world"))
        writing.run(WritingAction.PROOFREAD)
        assertEquals("Hello world", state.result)
        assertEquals(Tier.PRO, state.tier)
    }

    @Test
    fun `the cloud asks first, and declining sends nothing`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        writing.start("Draft a note to the team about Friday.", readOnly = false)
        writing.run(WritingAction.IMPROVE)
        assertTrue(state.consentNeeded)
        writing.declineCloud()
        assertFalse(state.consentNeeded)
        assertTrue(cloud.requests.isEmpty())

        cloud.enqueue(Step.Reply("Team, a quick note about Friday."))
        writing.approveCloud()
        assertEquals(Tier.CLOUD, state.tier)
        assertEquals("Team, a quick note about Friday.", state.result)
    }

    @Test
    fun `text that is too long is refused before anything runs`() {
        writing.start("x".repeat(WritingPrompt.MAX_CHARS + 1), readOnly = false)
        writing.run(WritingAction.SUMMARIZE)
        assertTrue(state.tooLong)
        assertTrue(base.requests.isEmpty() && pro.requests.isEmpty())
    }
}
