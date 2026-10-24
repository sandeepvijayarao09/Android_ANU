package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

/**
 * What makes an on-device turn read the whole chat again (ADR-097, ADR-114), measured with the runtime's counters
 * against a real Gemma 4 model. Each case runs the old shape of the request as a control, then the new one, and
 * reports the speed line's `conversation` reason, the tokens read and the first token's wait (tag AnuS1).
 */
@RunWith(AndroidJUnit4::class)
class ConversationReuseTest {

    @Serializable
    data class DayQuery(@Description("The day, as an ISO date") val day: String)

    @Serializable
    data class DayEvents(val events: List<String>)

    /** A made-up calendar with nothing on it, so a turn that looks gets a short answer. */
    private object EmptyCalendar : Tool<DayQuery, DayEvents> {
        override val name = "calendar_list_events"
        override val description = "List the user's calendar events on a day"
        override val input: KSerializer<DayQuery> = DayQuery.serializer()
        override val output: KSerializer<DayEvents> = DayEvents.serializer()
        override val dataClasses = setOf(DataClass.PERSONAL)
        override val untrustedContent: UntrustedContent? = null
        override fun risk(input: DayQuery) = RiskTier.READ
        override fun describe(input: DayQuery) = "List events on ${input.day}"
        override suspend fun invoke(input: DayQuery, context: ToolContext) = DayEvents(emptyList())
    }

    private object Approving : ApprovalGate {
        override suspend fun request(request: ApprovalRequest) = ApprovalDecision.Approved
    }

    /**
     * The first turn after midnight. Before ADR-114 the system prompt carried the date, so it changed and the chat was
     * read again (control); now the week's dates go with the message, and `ChatSession`'s next turn keeps the
     * conversation.
     */
    @Test
    fun theFirstTurnAfterMidnightKeepsTheConversation() = runBlocking {
        // Control: the old prompt, whose date line changed at midnight.
        brain.reset()
        val system = ChatSession.DEFAULT_SYSTEM
        val old = mutableListOf<ChatMessage>(ChatMessage.User(LONG_MESSAGE))
        answer(BrainRequest(system = "$system\n\nToday: Friday, 25 September 2026.", transcript = old.toList(), sampling = Sampling.Precise, maxOutputTokens = 32))
            .let { old += ChatMessage.Model(it) }
        old += ChatMessage.User("Reply with just the word: done")
        answer(BrainRequest(system = "$system\n\nToday: Saturday, 26 September 2026.", transcript = old.toList(), sampling = Sampling.Precise))
        val control = turns.last()
        report("midnight_old", control)
        assertTrue(control.conversation, control.conversation.startsWith("rebuilt (system"))

        // Now: a chat that offers a tool, so the week's dates are given, from 23:58 to 00:02.
        brain.reset()
        var now = ZonedDateTime.parse("2026-09-25T23:58:00-04:00[America/New_York]")
        val chat = ChatSession(
            Agent(Router(), BrainProvider { tier -> if (tier == Tier.BASE || tier == Tier.PRO) brain else null }, ToolBroker(listOf(EmptyCalendar), Approving)),
            toolsFor = { listOf(EmptyCalendar.name) },
            clock = { now },
        )
        val first = chat.send(LONG_MESSAGE, device).toList()
        S1.report("reuse_midnight_first_events", first.map { it::class.simpleName }.distinct().joinToString())
        assertTrue(first.lastOrNull().toString(), first.last() is AgentEvent.Completed)
        now = now.plusMinutes(4)
        val before = turns.size
        val second = chat.send("Reply with just the word: done", device).toList()
        assertTrue(second.lastOrNull().toString(), second.last() is AgentEvent.Completed)
        val after = turns[before]
        report("midnight_new", after)
        assertEquals("the first turn after midnight read the chat again", "kept", after.conversation)
    }

    /**
     * MCP tools in another order. Before ADR-114 a turn ranked a server's tools by its own words, so a follow-up could
     * list the same tools in another order and the chat was read again (control); now they're listed by name.
     */
    @Test
    fun theSameToolsInNameOrderKeepTheConversation() = runBlocking {
        val search = ToolSpec.of("mcp_notes_search_notes", "Notes (a service the user added): Search the user's notes", DayQuery.serializer())
        val add = ToolSpec.of("mcp_notes_add_note", "Notes (a service the user added): Add a note", DayQuery.serializer())
        for ((label, orders) in listOf("old" to listOf(listOf(search, add), listOf(add, search)), "new" to listOf(listOf(add, search), listOf(add, search)))) {
            brain.reset()
            val chat = mutableListOf<ChatMessage>(ChatMessage.User(LONG_MESSAGE))
            answer(BrainRequest(transcript = chat.toList(), tools = orders[0], sampling = Sampling.Precise, maxOutputTokens = 32)).let { chat += ChatMessage.Model(it) }
            chat += ChatMessage.User("Reply with just the word: done")
            answer(BrainRequest(transcript = chat.toList(), tools = orders[1], sampling = Sampling.Precise))
            val turn = turns.last()
            report("tools_$label", turn)
            if (label == "old") assertTrue(turn.conversation, turn.conversation.startsWith("rebuilt (tools"))
            else assertEquals("kept", turn.conversation)
        }
    }

    private fun report(key: String, turn: TurnSpeed) {
        S1.report("reuse_${key}_conversation", turn.conversation)
        S1.report("reuse_${key}_read_tokens", turn.read)
        S1.report("reuse_${key}_first_token_ms", turn.firstTokenMs)
    }

    private suspend fun answer(request: BrainRequest): String =
        brain.stream(request).toList().filterIsInstance<BrainEvent.TextDelta>().filterNot { it.thought }.joinToString("") { it.text }

    companion object {
        /**
         * About 250 tokens, so reading it again shows in the follow-up's count and wait. It asks for "thanks", not
         * "noted": that is a memory claim (ADR-089), and its repair would leave nothing to reuse.
         */
        private val LONG_MESSAGE = "Here are my notes from the week. " + List(10) { i ->
            "On day ${i + 1} I walked the harbor loop, had lunch by the old pier, and read on the east cliffs."
        }.joinToString(" ") + " Say only: thanks."

        private val device = DeviceState(online = false, availableTiers = setOf(Tier.BASE, Tier.PRO))
        private val turns = java.util.Collections.synchronizedList(mutableListOf<TurnSpeed>())
        private lateinit var holder: EngineHolder
        private lateinit var brain: LiteRtLmBrain

        @JvmStatic
        @BeforeClass
        fun setUp() {
            S1.assumeModelPresent()
            val config = EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())
            // The runtime's counters give each turn's speed line, with why it kept or rebuilt its conversation.
            holder = EngineHolder(config, S1.options().copy(benchmark = true))
            brain = LiteRtLmBrain(
                id = BrainId.PRO,
                capabilities = BrainCapabilities(BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true),
                engines = holder,
                onTurn = turns::add,
            )
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::brain.isInitialized) runBlocking { brain.reset() }
            if (::holder.isInitialized) holder.close()
        }
    }
}
