package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.Cascade
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.InMemoryTranscriptStore
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.BrainId
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
class ChatViewModelTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
    private var device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO))
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)
        viewModel = ChatViewModel(ChatSession(Cascade(Router(), BrainProvider { brains[it] })), device = { device })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val bubbles get() = viewModel.state.value.bubbles

    @Test
    fun `a message streams into an answer labelled with its tier`() {
        base.enqueue(Step.Reply(listOf("Hel", "lo!")))
        viewModel.send("Hi")
        assertEquals(listOf("Hi", "Hello!"), bubbles.map { it.text })
        assertEquals(Tier.BASE, bubbles.last().tier)
        assertFalse(viewModel.state.value.busy)
        assertFalse(bubbles.last().streaming)
    }

    @Test
    fun `cloud use waits for consent, and approval sends it`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        viewModel.send("Plan my week")
        assertEquals("Plan my week", viewModel.state.value.consentFor)
        assertEquals(listOf("Plan my week"), bubbles.map { it.text }) // no empty answer bubble left behind
        assertTrue(cloud.requests.isEmpty())

        cloud.enqueue(Step.Reply("Here's a plan."))
        viewModel.approveCloud()
        assertNull(viewModel.state.value.consentFor)
        assertEquals(Tier.CLOUD, bubbles.last().tier)
        assertEquals("Here's a plan.", bubbles.last().text)
    }

    @Test
    fun `declining consent sends nothing`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        viewModel.send("Plan my week")
        viewModel.declineCloud()
        assertTrue(cloud.requests.isEmpty())
        assertEquals("Kept on this phone. Nothing was sent.", bubbles.last().text)
    }

    @Test
    fun `retry with Pro replaces the last answer`() {
        base.enqueue(Step.Reply("short"))
        pro.enqueue(Step.Reply("thorough"))
        viewModel.send("Explain tides")
        viewModel.retryWithPro()
        assertEquals(listOf("Explain tides", "thorough"), bubbles.map { it.text })
        assertEquals(Tier.PRO, bubbles.last().tier)
    }

    @Test
    fun `escalation shows its progress and the final tier`() {
        base.enqueue(Step.Fail(IllegalStateException("out of memory")))
        pro.enqueue(Step.Reply("Answer from Pro"))
        viewModel.send("Hi")
        assertEquals("Answer from Pro", bubbles.last().text)
        assertEquals(Tier.PRO, bubbles.last().tier)
        assertNull(bubbles.last().note)
    }

    @Test
    fun `with no model ready the user is told where to fix it`() {
        device = DeviceState(online = true, availableTiers = emptySet())
        viewModel.send("Hi")
        assertTrue(bubbles.last().failed)
        assertTrue(bubbles.last().text.contains("Settings"))
    }

    @Test
    fun `the saved conversation is shown when the screen opens`() {
        val store = InMemoryTranscriptStore(listOf(ChatMessage.User("Remember milk"), ChatMessage.Model("Noted.")))
        val brains = mapOf(Tier.BASE to base)
        val reopened = ChatViewModel(ChatSession(Cascade(Router(), BrainProvider { brains[it] }), store), device = { device })
        assertEquals(listOf("Remember milk", "Noted."), reopened.state.value.bubbles.map { it.text })
        assertFalse(reopened.state.value.busy)
    }

    @Test
    fun `stop cancels the running answer`() {
        base.enqueue(Step.Hang(listOf("Once upon")))
        viewModel.send("Tell me a story")
        assertTrue(viewModel.state.value.busy)
        viewModel.stop()
        assertEquals("Stopped", bubbles.last().note)
        assertFalse(viewModel.state.value.busy)
        assertEquals(1, base.cancellations)
    }
}
