package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseOpinionTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val asked = mutableListOf<String>()
    private var says: Boolean? = true
    private val opinion = BaseOpinion { text -> asked += text; says ?: error("no answer") }
    private val agent = Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro)[it] }, ToolBroker(emptyList(), RecordingGate()), baseOpinion = opinion)
    private val pair = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO), baseOnTpu = true, proOnGpu = true)
    private val light = RouteRequest("Reply with just the word pong", Skill.CHAT, setOf(DataClass.PERSONAL), promptTokens = 100)

    private suspend fun run(device: DeviceState = pair, route: RouteRequest = light) =
        agent.run(route, device, system = "You are Anu.", history = listOf(ChatMessage.User(route.text)), tools = emptyList(), turnKey = "t1").toList()

    @Test
    fun `the answer is read as one word`() {
        assertEquals(true, BaseOpinion.parse("PRO"))
        assertEquals(false, BaseOpinion.parse(" base\n"))
        assertNull(BaseOpinion.parse("I can do that"))
        assertEquals(BaseOpinion.PROMPT, BaseOpinion.request("hey").system)
    }

    @Test
    fun `beside Pro on the GPU, Base's PRO sends a Base request to Pro (ADR-106)`() = runTest {
        pro.enqueue(Step.Reply("pong"))
        val events = run()
        assertEquals(listOf(light.text), asked)
        assertEquals(Tier.PRO, (events.last() as AgentEvent.Completed).tier)
        assertTrue(base.requests.isEmpty())
    }

    @Test
    fun `Base's BASE, or no answer, keeps the rules' choice`() = runTest {
        says = false
        base.enqueue(Step.Reply("pong"))
        assertEquals(Tier.BASE, (run().last() as AgentEvent.Completed).tier)
        says = null
        base.enqueue(Step.Reply("pong"))
        assertEquals(Tier.BASE, (run().last() as AgentEvent.Completed).tier)
    }

    @Test
    fun `it isn't asked without Pro on the GPU, for a pin, or for a request the rules already gave Pro`() = runTest {
        base.enqueue(Step.Reply("pong"), Step.Reply("pong"))
        run(device = pair.copy(proOnGpu = false))
        run(route = light.copy(pinnedTier = Tier.BASE))
        pro.enqueue(Step.Reply("Done."))
        run(route = light.copy(text = "Find a time next week for me, Maya and Raj, then email them an invite", skill = Skill.SCHEDULING))
        assertTrue(asked.toString(), asked.isEmpty())
    }
}
