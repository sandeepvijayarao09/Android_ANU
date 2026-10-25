package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.agent.ActionClaims
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.agent.ToolOutcome
import io.github.sandeepvijayarao09.anu.core.memory.RememberInput
import io.github.sandeepvijayarao09.anu.core.memory.RememberOutput
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.model.echoesFraming
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repair-note probe (ADR-124): where must the claim check's repair note go for E4B to follow it? With the note in the
 * system prompt, E4B's greedy repair wrote "I have noted that…" again both times on the emulator (ADR-089). For each
 * made-up message, the probe takes the request the chat really sends, then runs the repair twice on the same chat,
 * greedy as the agent does: once with the note in the system prompt, as before, and once at the end of the newest
 * message, as now. It reports each answer, its tool calls, and whether it still claims a save. It passes either way:
 * it measures.
 */
@RunWith(AndroidJUnit4::class)
class RepairNoteProbeTest {

    /** A made-up message; [declined] adds the user declining `memory_remember` after it, as on the emulator. */
    private class Case(val key: String, val text: String, val declined: Boolean = false)

    private val cases = listOf(
        Case("vegetarian", "By the way, I'm vegetarian, so please keep that in mind whenever you suggest recipes or restaurants."),
        Case("two_people", "Also, I usually cook for two people, so please keep that in mind whenever you suggest recipes or portion sizes."),
        Case("night_shifts", "One more thing: I work night shifts on weekends, so keep that in mind when you plan things for me."),
        Case("coffee", "Just so you know, I don't drink coffee, so keep that in mind when you suggest places to meet."),
        Case("spanish", "I'm learning Spanish this year, so please keep that in mind when you suggest podcasts or books."),
        Case("evenings", "My partner Alex usually finishes work at 6, so keep that in mind when you plan our evenings."),
        Case("birthday", "Please remember that my sister Priya's birthday is on May 3."),
        Case("declined", "Remember that I buy oat milk every week", declined = true),
    )

    /** `memory_remember` as the model sees it in the app: the real name, description and schema. It never runs. */
    private object RememberLike : Tool<RememberInput, RememberOutput> {
        override val name = ActionClaims.REMEMBER_TOOL
        override val description = "Save a lasting fact the user asked you to remember. It stays on this phone, in the Memory tab."
        override val input = RememberInput.serializer()
        override val output = RememberOutput.serializer()
        override val dataClasses = setOf(DataClass.PERSONAL)
        override val untrustedContent: UntrustedContent? = null
        override fun risk(input: RememberInput) = RiskTier.WRITE_PRIVATE
        override fun describe(input: RememberInput) = "Remember ${input.what}: ${input.value}"
        override suspend fun invoke(input: RememberInput, context: ToolContext) = error("the probe never runs a tool")
    }

    /** Keeps the chat's first request and answers without a model, so the probe gets the real request for free. */
    private class Capturing : Brain {
        var request: BrainRequest? = null
        override val id = BrainId.PRO
        override val capabilities = BrainCapabilities(location = BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true)

        override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
            if (this@Capturing.request == null) this@Capturing.request = request
            emit(BrainEvent.TextDelta("Okay."))
            emit(BrainEvent.Usage(contextTokens = 1))
            emit(BrainEvent.Finished(StopReason.COMPLETE))
        }
    }

    /** The request the chat sends for [case], with the app's system prompt, time and tools, ready for its repair. */
    private suspend fun requestFor(case: Case): BrainRequest {
        val capturing = Capturing()
        val never = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest) = ApprovalDecision.Denied()
        }
        val chat = ChatSession(
            Agent(Router(), BrainProvider { tier -> if (tier == Tier.PRO) capturing else null }, ToolBroker(listOf(RememberLike), never)),
            // As the app offers them: memory's tools only to the memory skill.
            toolsFor = { skill -> if (skill == Skill.MEMORY) listOf(RememberLike.name) else emptyList() },
        )
        chat.send(case.text, DeviceState(online = false, availableTiers = setOf(Tier.PRO))).toList()
        val request = requireNotNull(capturing.request) { "the chat sent nothing for ${case.key}" }
        if (!case.declined) return request
        val call = ToolCall(
            ActionClaims.REMEMBER_TOOL,
            buildJsonObject { put("about", "user"); put("what", "buys_weekly"); put("value", "oat milk"); put("section", "routines") },
        )
        val refused = ToolOutcome.Rejected(
            "The user was shown this and declined (The user declined), so nothing was done. Don't try again unless they ask.",
            code = "declined",
        )
        return request.copy(transcript = request.transcript + ChatMessage.Model("", listOf(call)) + ChatMessage.Tool(listOf(ToolResult(call.name, refused.toResultJson()))))
    }

    private class Answer(val text: String, val calls: List<ToolCall>, val firstWordMs: Long)

    private suspend fun answer(brain: LiteRtLmBrain, request: BrainRequest): Answer {
        brain.reset()
        val started = System.nanoTime()
        var first = 0L
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        brain.stream(request).collect { event ->
            when (event) {
                is BrainEvent.TextDelta -> if (!event.thought) {
                    if (first == 0L) first = System.nanoTime()
                    text.append(event.text)
                }
                is BrainEvent.ToolCallRequested -> calls += event.call
                else -> Unit
            }
        }
        return Answer(text.toString().trim(), calls, if (first == 0L) -1 else (first - started) / 1_000_000)
    }

    @Test
    fun theRepairNoteInTheMessageAgainstTheSystemPrompt() = runBlocking {
        S1.assumeModelPresent()
        val config = EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())
        val capabilities = BrainCapabilities(location = BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true)
        val stillClaims = mutableMapOf<String, Int>()
        val tried = mutableMapOf<String, Int>()
        EngineHolder(config).use { engine ->
            val brain = LiteRtLmBrain(BrainId.PRO, capabilities, engine)
            for (case in cases) {
                val request = requestFor(case)
                val note = ActionClaims.note(setOf(ActionClaims.Claim.MEMORY), canRemember = request.tools.any { it.name == ActionClaims.REMEMBER_TOOL })
                val variants = mapOf(
                    // How the agent repaired before ADR-124.
                    "system" to request.copy(system = "${request.system}\n\n$note", sampling = Sampling.Precise),
                    // How it repairs now.
                    "message" to request.copy(note = note, sampling = Sampling.Precise),
                )
                for ((where, repair) in variants) {
                    val reply = answer(brain, repair)
                    val claims = ActionClaims.claimsMemory(reply.text)
                    tried[where] = (tried[where] ?: 0) + 1
                    if (claims) stillClaims[where] = (stillClaims[where] ?: 0) + 1
                    val key = "repair_probe_${case.key}_$where"
                    S1.report("${key}_answer", reply.text.take(300).replace("\n", " / ").ifEmpty { "(none)" })
                    S1.report("${key}_calls", reply.calls.joinToString(" | ") { "${it.name} ${it.arguments}" }.ifEmpty { "none" })
                    S1.report("${key}_claims", claims)
                    S1.report("${key}_echoes_note", repair.echoesFraming(reply.text))
                    S1.report("${key}_first_word_ms", reply.firstWordMs)
                }
            }
        }
        for ((where, count) in tried) S1.report("repair_probe_still_claims_$where", "${stillClaims[where] ?: 0} of $count")
    }
}
