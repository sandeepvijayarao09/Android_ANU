package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorTest {

    /** Records each turn it's handed, and answers it. */
    private class Agents : Orchestrator {
        val turns = mutableListOf<OrchestratedTurn>()
        override val skills = setOf(Skill.AGENTS, Skill.BRIEF)
        override fun run(turn: OrchestratedTurn): Flow<AgentEvent> {
            turns += turn
            val said = "Done by the agents."
            return flowOf(AgentEvent.TextDelta(Tier.PRO, said), AgentEvent.Completed(Tier.PRO, said, listOf(ChatMessage.Model(said))))
        }
    }

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val agents = Agents()
    private val session = ChatSession(
        Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro)[it] }, ToolBroker(emptyList(), RecordingGate())),
        orchestrator = agents,
    )
    private val device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO))

    @Test
    fun `an agents turn gets the chat's last exchanges, and the next plain question goes back to the chat's own agent`() = runTest {
        base.enqueue(Step.Reply("Hi Maya's friend!"), Step.Reply("Try Miso."))
        session.send("Hello", device).toList()
        session.send("Check my calendar for Friday and text Maya I'm free", device).toList()

        val turn = agents.turns.single()
        assertEquals(Skill.AGENTS, turn.skill)
        assertEquals("User: Hello\nAnu: Hi Maya's friend!", turn.recent)

        session.send("What is a good name for a cat?", device).toList()
        assertEquals("not the agents again", 1, agents.turns.size)
        assertEquals(ChatMessage.Model("Try Miso."), session.history().last())
    }

    @Test
    fun `recent chat keeps the last words of each side, cut short, and leaves tool turns out`() {
        val long = "x".repeat(400)
        val messages = listOf(
            ChatMessage.User("one"), ChatMessage.Model("", listOf()), ChatMessage.Tool(emptyList()),
            ChatMessage.Model("two"), ChatMessage.User(long),
        )
        val recent = recentChat(messages, max = 2, chars = 10)!!
        assertEquals("Anu: two\n" + "User: xxxx…", recent)
        assertNull(recentChat(emptyList()))
        assertTrue(recentChat(messages)!!.startsWith("User: one"))
    }
}
