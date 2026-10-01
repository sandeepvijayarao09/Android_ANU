package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.ReplyWords
import io.github.sandeepvijayarao09.anu.core.model.Screen
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Reply to this" on the real model (EXP-2, ADR-092): a made-up chat screen drawn on the Mac, where Maya Chen asks
 * "Are we still on for lunch at 12:30?", goes with "Reply to this" through a real chat turn, as the assistant card sends
 * it. A run passes when the answer is only the reply, ready to send: short, about the lunch, with no "Here's a reply"
 * before it. A made-up settings screen, with nothing to reply to, should answer "No message to reply to." Each runs
 * [RUNS] times, and each answer goes to AnuS1. Draw the screens with scripts/MadeUpScreens.java, push them, and use
 * `visionBackend=CPU` on the emulator:
 *   adb push reply-chat.jpg reply-settings.jpg /data/local/tmp/anu/photos/
 */
@RunWith(AndroidJUnit4::class)
class ReplyDraftEvalTest {

    @Test
    fun aReplyAboutTheScreenIsOnlyTheReply() = runBlocking {
        S1.assumeModelPresent()
        val chatScreen = File("$DIR/reply-chat.jpg")
        val settingsScreen = File("$DIR/reply-settings.jpg")
        assumeTrue("No screens in $DIR; skipping", chatScreen.canRead() && settingsScreen.canRead())
        val vision = if (InstrumentationRegistry.getArguments().getString("visionBackend") == "CPU") Backend.CPU() else Backend.GPU()
        val config = EngineConfig(
            modelPath = S1.modelPath,
            backend = S1.backend(),
            visionBackend = vision,
            maxNumTokens = 4096,
            maxNumImages = 1,
            cacheDir = S1.cacheDir(),
        )
        val capabilities = BrainCapabilities(location = BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true)
        EngineHolder(config).use { engine ->
            val brain = LiteRtLmBrain(BrainId.PRO, capabilities, engine)
            var replies = 0
            var nothing = 0
            repeat(RUNS) { run ->
                val reply = ask(brain, chatScreen.readBytes())
                S1.report("reply_eval_chat_$run", reply.take(240).replace("\n", " / "))
                if (onlyAReply(reply)) replies++
                val none = ask(brain, settingsScreen.readBytes())
                S1.report("reply_eval_settings_$run", none.take(240).replace("\n", " / "))
                if (ReplyWords.nothingToReply(none)) nothing++
            }
            S1.report("reply_eval_passed", "chat $replies of $RUNS, settings $nothing of $RUNS")
            assertTrue("at least one run answers the chat with only the reply", replies > 0)
        }
    }

    /** One question from the card about [screen], in a chat of its own; the answer's text. */
    private suspend fun ask(brain: LiteRtLmBrain, screen: ByteArray): String {
        val chat = ChatSession(
            Agent(Router(), BrainProvider { tier -> if (tier == Tier.BASE || tier == Tier.PRO) brain else null }, ToolBroker(emptyList(), REFUSING)),
        )
        brain.reset()
        val events = chat.send(
            "Reply to this",
            DeviceState(online = false, availableTiers = setOf(Tier.BASE, Tier.PRO)),
            screen = Screen("Messages", System.currentTimeMillis(), screen),
        ).toList()
        return (events.lastOrNull() as? AgentEvent.Completed)?.text ?: events.lastOrNull().toString()
    }

    private fun onlyAReply(answer: String): Boolean {
        val text = answer.trim()
        return text.isNotEmpty() && text.length <= 240 && !ReplyWords.nothingToReply(text) &&
            !PREAMBLE.containsMatchIn(text) && ABOUT_LUNCH.containsMatchIn(text)
    }

    private companion object {
        const val RUNS = 3
        const val DIR = "/data/local/tmp/anu/photos"

        val REFUSING = ApprovalGate { ApprovalDecision.Denied("eval: nothing is sent") }

        /** Words around a reply rather than the reply: "Here's a reply:", "Sure, here", "You could say". */
        val PREAMBLE = Regex("^(?:here(?:'s| is)|sure[,!]? here|reply:|draft:|you could (?:say|reply))|\\bhere's a (?:reply|response)\\b", RegexOption.IGNORE_CASE)

        /** An answer to "Are we still on for lunch at 12:30?". */
        val ABOUT_LUNCH = Regex("lunch|12:30|see you|\\byes\\b|\\byep\\b|\\bsure\\b|still on|works|sounds good|can't make|\\bno\\b", RegexOption.IGNORE_CASE)
    }
}
