package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalCenter
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.InMemoryTranscriptStore
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        viewModel = ChatViewModel(ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter()))), device = { device })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val bubbles get() = viewModel.state.value.bubbles

    @Test
    fun `a long chat says that older messages are left out`() {
        val brains = mapOf(Tier.BASE to base)
        val session = ChatSession(
            Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())),
            window = io.github.sandeepvijayarao09.anu.core.agent.ContextWindow(budget = 600),
        )
        val chat = ChatViewModel(session, device = { device })
        repeat(4) { base.enqueue(Step.Reply("r".repeat(350))) }
        chat.send("First " + "x".repeat(340))
        assertFalse(chat.state.value.trimmed)
        repeat(3) { chat.send("More " + "x".repeat(340)) }
        assertTrue(chat.state.value.trimmed)
    }

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

    private fun withFacts(): ChatViewModel {
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)
        val session = ChatSession(
            Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())),
            relevant = { _, _ -> listOf(io.github.sandeepvijayarao09.anu.core.model.RelevantFact("manager: Maya Chen", sensitive = false)) },
        )
        return ChatViewModel(session, device = { device })
    }

    @Test
    fun `the consent sheet lists the facts, and approving shares exactly those`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        val chat = withFacts()
        chat.send("Lunch ideas for my manager")
        assertEquals(listOf("manager: Maya Chen"), chat.state.value.consentFacts)
        cloud.enqueue(Step.Reply("Try the new Thai place."))
        chat.approveCloud(shareFacts = true)
        assertEquals(listOf("manager: Maya Chen"), cloud.requests.last().sharedFacts)
        assertEquals(emptyList<String>(), chat.state.value.consentFacts)
    }

    @Test
    fun `keeping the facts on the phone sends none of them`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        val chat = withFacts()
        chat.send("Lunch ideas for my manager")
        cloud.enqueue(Step.Reply("Try the new Thai place."))
        chat.approveCloud(shareFacts = false)
        assertEquals(emptyList<String>(), cloud.requests.last().sharedFacts)
    }

    @Test
    fun `declining consent sends nothing`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        viewModel.send("Plan my week")
        viewModel.declineCloud()
        assertTrue(cloud.requests.isEmpty())
        assertEquals("Kept on this phone. Nothing was sent.", bubbles.last().text)
        assertEquals("declining isn't a failure", 0, viewModel.state.value.failures)
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
        assertEquals("the turn itself succeeded", 0, viewModel.state.value.failures)
    }

    @Test
    fun `with no model ready the user is told where to fix it`() {
        device = DeviceState(online = true, availableTiers = emptySet())
        viewModel.send("Hi")
        assertTrue(bubbles.last().failed)
        assertTrue(bubbles.last().text.contains("Settings"))
        assertEquals(1, viewModel.state.value.failures)
    }

    @Test
    fun `the saved conversation is shown when the screen opens`() {
        val store = InMemoryTranscriptStore(listOf(ChatMessage.User("Remember milk"), ChatMessage.Model("Noted.")))
        val brains = mapOf(Tier.BASE to base)
        val reopened = ChatViewModel(
            ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())), store),
            device = { device },
        )
        assertEquals(listOf("Remember milk", "Noted."), reopened.state.value.bubbles.map { it.text })
        assertFalse(reopened.state.value.busy)
    }

    @Serializable
    data class Invite(val title: String, val attendees: List<String>)

    @Serializable
    data class Created(val id: String)

    private class InviteTool : Tool<Invite, Created> {
        var created = 0
        override val name = "calendar_create_event"
        override val description = "Create an event"
        override val input = Invite.serializer()
        override val output = Created.serializer()
        override val dataClasses = setOf(DataClass.PERSONAL)
        override val returnsUntrustedContent = false
        override fun risk(input: Invite) = RiskTier.WRITE_EXTERNAL
        override fun describe(input: Invite) = "Create \"${input.title}\" and invite ${input.attendees.joinToString()}"
        override suspend fun invoke(input: Invite, context: ToolContext): Created = Created("e${++created}")
    }

    @Test
    fun `an invite waits for approval, shows the exact payload, and runs once approved`() {
        val tool = InviteTool()
        val center = ApprovalCenter()
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro)
        val scheduling = ChatViewModel(
            ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(listOf(tool), center)), toolsFor = { listOf(tool.name) }),
            device = { device },
            approvals = center,
        )
        val call = ToolCall("calendar_create_event", buildJsonObject {
            put("title", "Design review")
            putJsonArray("attendees") { add(JsonPrimitive("maya@example.com")) }
        })
        pro.enqueue(Step.Reply(listOf(""), toolCalls = listOf(call)), Step.Reply("Invite sent."))
        scheduling.send("Schedule a design review with Maya")

        val approval = scheduling.state.value.approval!!
        assertEquals("Create \"Design review\" and invite maya@example.com", approval.summary)
        assertEquals(listOf("title: Design review", "attendees: maya@example.com"), payloadLines(approval.payload))
        assertEquals(listOf("title: x"), payloadLines(buildJsonObject { put("title", "x"); put("location", kotlinx.serialization.json.JsonNull) }))
        val order = kotlinx.serialization.json.Json.parseToJsonElement("""{"store":"shoes.example.com","items":[{"item_id":"shoe_10","quantity":1},{"item_id":"sock_m","quantity":2}]}""")
        assertEquals(
            listOf("store: shoes.example.com", "items: item_id: shoe_10, quantity: 1; item_id: sock_m, quantity: 2"),
            payloadLines(order as kotlinx.serialization.json.JsonObject),
        )
        assertEquals(0, tool.created)

        scheduling.approve(approval.id)
        assertEquals(1, tool.created)
        assertNull(scheduling.state.value.approval)
        assertEquals("Invite sent.", scheduling.state.value.bubbles.last().text)
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
