package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.DocumentReader
import io.github.sandeepvijayarao09.anu.core.model.Pdf
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike S13 (ADR-095): a long PDF read in pieces on the real model. A made-up 12-page lease, about 3,000 words, goes
 * through a real chat turn with each question, as Talk sends it: its pieces are read in conversations of their own,
 * then the chat answers from their notes. Each answer, its notes and its time go to AnuS1. A question passes when the
 * answer holds the fact from its page. No file is needed: the pages are the text Android would give.
 */
@RunWith(AndroidJUnit4::class)
class DocumentReadEvalTest {

    @Test
    fun aLongPdfIsAnsweredFromItsPieces() = runBlocking {
        S1.assumeModelPresent()
        val config = EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())
        val capabilities = BrainCapabilities(location = BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true)
        EngineHolder(config).use { engine ->
            val brain = LiteRtLmBrain(BrainId.PRO, capabilities, engine)
            val reader = object : DocumentReader {
                override fun canRead() = true

                override fun read(request: BrainRequest): Flow<BrainEvent> = brain.once(request)
            }
            var passed = 0
            QUESTIONS.forEachIndexed { index, (question, expected) ->
                val chat = ChatSession(
                    Agent(Router(), BrainProvider { tier -> if (tier == Tier.BASE || tier == Tier.PRO) brain else null }, ToolBroker(emptyList(), REFUSING)),
                    documentReader = reader,
                )
                brain.reset()
                val started = SystemClock.elapsedRealtime()
                val events = chat.send(question, DeviceState(online = false, availableTiers = setOf(Tier.BASE, Tier.PRO)), pdf = Pdf("Lease.pdf", PAGES.size, PAGES)).toList()
                val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                val notes = events.filterIsInstance<AgentEvent.DocumentRead>().singleOrNull()?.document?.text.orEmpty()
                val answer = (events.lastOrNull() as? AgentEvent.Completed)?.text ?: events.lastOrNull().toString()
                val pieces = events.filterIsInstance<AgentEvent.ReadingDocument>().size
                S1.report("document_eval_${index}_notes", notes.take(400).replace("\n", " / "))
                S1.report("document_eval_${index}_answer", answer.take(240).replace("\n", " / "))
                S1.report("document_eval_${index}_time", "%.1f s, %d pieces".format(seconds, pieces))
                if (expected.containsMatchIn(answer)) passed++
            }
            S1.report("document_eval_passed", "$passed of ${QUESTIONS.size}")
            assertTrue("at least two answers hold their page's fact", passed >= 2)
        }
    }

    private companion object {
        val REFUSING = ApprovalGate { ApprovalDecision.Denied("eval: nothing runs") }

        /** Filler a lease has between its facts, so the pages run long, as a real one's do. */
        private val CLAUSE = "The Tenant shall keep the Premises clean and in good repair, shall not make alterations without the " +
            "Landlord's written consent, and shall promptly report any damage or need for repair. "

        private fun page(number: Int, fact: String) = "Page $number. " + CLAUSE.repeat(8) + fact + " " + CLAUSE.repeat(8)

        /** A made-up lease between made-up example.com people. Its facts sit on pages 3, 7 and 10. */
        val PAGES = (1..12).map { number ->
            page(
                number,
                when (number) {
                    1 -> "This lease is between Maya Chen (maya.chen@example.com), the Landlord, and Sam Rivera, the Tenant."
                    3 -> "The term of this lease begins on September 1, 2026 and ends on August 31, 2027."
                    7 -> "Monthly rent is \$1,850, due on the first day of each month, with a late fee of \$75 after the fifth."
                    10 -> "The Tenant may keep one cat. No dogs or other animals are allowed without written consent."
                    else -> ""
                },
            )
        }

        val QUESTIONS = listOf(
            "How much is the rent, and when is it due?" to Regex("1,?850"),
            "Can I have a pet?" to Regex("\\bcat\\b", RegexOption.IGNORE_CASE),
            "When does the lease end?" to Regex("August 31|2027", RegexOption.IGNORE_CASE),
        )
    }
}
