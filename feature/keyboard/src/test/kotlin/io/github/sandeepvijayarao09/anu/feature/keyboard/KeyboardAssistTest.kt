package io.github.sandeepvijayarao09.anu.feature.keyboard

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardAssistTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
    // The phone could reach Gemini: the keyboard must still never ask it.
    private val device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO, Tier.CLOUD))
    private val brains = mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val assist = KeyboardAssist(Cascade(Router(), BrainProvider { brains[it] }), { device }, scope, memory = { "- manager: Maya Chen" })

    private val state get() = assist.state.value

    @Test
    fun `a rewrite becomes a preview, and only accepting gives the text to commit`() {
        base.enqueue(Step.Reply(listOf("Sure! Here's the corrected text:\n\n", "I have a cat.")))
        assist.run(KeyboardAction.PROOFREAD, FieldText(before = "i has a cat "))
        val ready = state as AssistState.Ready
        assertEquals("I have a cat.", ready.result)
        assertTrue(ready.replaces)
        assertEquals(Tier.BASE, ready.tier)
        val request = base.requests.single()
        assertTrue((request.transcript.single() as ChatMessage.User).text.endsWith("<text>\ni has a cat\n</text>"))
        assertEquals(Sampling.Precise, request.sampling)
        assertNull("proofreading doesn't need memory", request.memory)

        val (plan, output) = assist.accept(FieldText(before = "i has a cat "))!!
        assertEquals(EditMode.REPLACE_BEFORE, plan.mode)
        assertEquals("i has a cat ", plan.source)
        assertEquals("I have a cat. ", output)
        assertEquals(AssistState.Idle, state)
    }

    @Test
    fun `nothing is replaced when the field changed after the preview`() {
        base.enqueue(Step.Reply("I have a cat."))
        assist.run(KeyboardAction.IMPROVE, FieldText(before = "i has a cat"))
        assertNull(assist.accept(FieldText(before = "i has a cat and a dog")))
        assertTrue((state as AssistState.Note).text.startsWith("Your text changed"))
    }

    @Test
    fun `a failed answer moves up to Pro and never to the cloud`() {
        base.enqueue(Step.Reply(""), Step.Reply(""))
        pro.enqueue(Step.Reply(""), Step.Reply(""))
        assist.run(KeyboardAction.SHORTER, FieldText(before = "A long sentence that goes on."))
        assertTrue(state is AssistState.Note)
        assertEquals(2, base.requests.size)
        assertEquals(2, pro.requests.size)
        assertTrue("the cloud was never asked", cloud.requests.isEmpty())
    }

    @Test
    fun `continue writes the next sentence and inserts it after the text`() {
        base.enqueue(Step.Reply("See you at 3. Bring the slides."))
        assist.run(KeyboardAction.CONTINUE, FieldText(before = "See you at 3."))
        val ready = state as AssistState.Ready
        assertEquals("Bring the slides.", ready.result)
        assertEquals(false, ready.replaces)
        assertEquals(KeyboardPrompt.CONTINUE_SYSTEM, base.requests.single().system)
        assertEquals(" Bring the slides.", assist.accept(FieldText(before = "See you at 3."))!!.second)
    }

    @Test
    fun `reply sees the chat as data, with the draft, and names whom it's to`() {
        base.enqueue(Step.Reply("Running 10 minutes late, sorry!"))
        val chat = ChatContext("Maya Chen", listOf(ChatContext.Line("Maya Chen", "Are you still coming at 6?", fromMe = false)))
        assist.run(KeyboardAction.REPLY, FieldText(before = "late 10 min"), chat)
        val ready = state as AssistState.Ready
        assertEquals("Maya Chen", ready.chatTitle)
        assertTrue(ready.replaces)
        val ask = (base.requests.single().transcript.single() as ChatMessage.User).text
        assertTrue(ask.contains("<chat>\nMaya Chen: Are you still coming at 6?\n</chat>"))
        assertTrue(ask.contains("<draft>\nlate 10 min\n</draft>"))
        assertEquals("- manager: Maya Chen", base.requests.single().memory)
    }

    @Test
    fun `reply with no chat and nothing typed, or a rewrite of nothing, asks nothing of the model`() {
        assist.run(KeyboardAction.REPLY, FieldText(before = ""))
        assertTrue((state as AssistState.Note).text.startsWith("No message to reply to"))
        assist.run(KeyboardAction.IMPROVE, FieldText(before = "  "))
        assertTrue(state is AssistState.Note)
        assist.run(KeyboardAction.CONTINUE, FieldText(before = ""))
        assertTrue(state is AssistState.Note)
        assertTrue(base.requests.isEmpty())
    }

    @Test
    fun `stop cancels the model and clears the text`() {
        base.enqueue(Step.Hang(listOf("I have")))
        assist.run(KeyboardAction.IMPROVE, FieldText(before = "i has a cat"))
        assertEquals("I have", (state as AssistState.Working).partial)
        assist.stop()
        assertEquals(AssistState.Idle, state)
        assertEquals(1, base.cancellations)
    }

    @Test
    fun `no model on the phone says so`() {
        val none = KeyboardAssist(Cascade(Router(), BrainProvider { brains[it] }), { DeviceState(online = true, availableTiers = setOf(Tier.CLOUD)) }, scope)
        none.run(KeyboardAction.IMPROVE, FieldText(before = "i has a cat"))
        assertTrue((none.state.value as AssistState.Note).text.startsWith("No model is on this phone"))
        assertTrue(cloud.requests.isEmpty())
    }
}
