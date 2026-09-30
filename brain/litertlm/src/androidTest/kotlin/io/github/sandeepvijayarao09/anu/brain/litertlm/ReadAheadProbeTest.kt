package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * ENG-11's probe: can warm start read a chat ahead, so the first message after a reload doesn't wait for it? It has
 * two gates (the plan's Eng D13). First, the next message's first word comes at least 30% sooner: a made-up 20-message
 * chat's conversation is created with and without `prefillPrefaceOnInit`, reporting how many tokens it holds when
 * created, how long creating took, and the first word of the next message. Without reading ahead, the user waits for
 * both; with it, creating happens before they send, so they wait for the first word only. Second, a read-ahead for the
 * wrong skill must free the model within 1 s: `createConversation` returns only when it's done, so nothing can stop it
 * part way, and the probe times a message for another skill sent 1 s into a read-ahead against the same message alone.
 * Reports to AnuS1; asserts nothing.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class ReadAheadProbeTest {

    private val system = "You are Anu, a private assistant running on the user's phone. Be concise, warm and accurate."

    /** Ten made-up exchanges: the kind of chat warm start would find after a reload. */
    private val history: List<Message> = (1..10).flatMap { i ->
        listOf(
            Message.user("Day $i of planning the Westmere trip: which of the lighthouse walks is shortest, and is there parking near the harbor? Also remind me what we decided about the ferry times and the picnic on the cliffs."),
            Message.model("For day $i, the harbor loop is the shortest at about 2 miles, with free parking by the old pier. You chose the 9:15 ferry out and the 4:40 back, and the picnic is on the east cliffs if the wind is under 15 mph. Bring the thermos and the blue blanket."),
        )
    }

    private val greedy = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)

    private fun chat(ahead: Boolean) =
        ConversationConfig(systemInstruction = Contents.of(system), initialMessages = history, samplerConfig = greedy, prefillPrefaceOnInit = ahead)

    /** Sends [text] and waits for the whole reply: the first word's time and the whole reply's, in ms. */
    private fun ask(conversation: Conversation, text: String): Pair<Long, Long> {
        val sent = SystemClock.elapsedRealtime()
        val first = AtomicLong()
        val done = CountDownLatch(1)
        conversation.sendMessageAsync(
            Contents.of(text),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    first.compareAndSet(0, SystemClock.elapsedRealtime())
                }

                override fun onDone() = done.countDown()

                override fun onError(throwable: Throwable) {
                    S1.report("read_ahead_error", throwable.toString())
                    done.countDown()
                }
            },
        )
        done.await(5, TimeUnit.MINUTES)
        val end = SystemClock.elapsedRealtime()
        return (first.get().takeIf { it > 0 } ?: end) - sent to end - sent
    }

    /** A short message for another skill, in a conversation of its own: its first word, in ms from asking. */
    private fun otherSkill(engine: Engine): Long {
        val asked = SystemClock.elapsedRealtime()
        val conversation = engine.createConversation(
            ConversationConfig(systemInstruction = Contents.of("You are Anu. Answer in one short line."), samplerConfig = greedy),
        )
        return conversation.use {
            val created = SystemClock.elapsedRealtime() - asked
            created + ask(it, "Say hello.").first
        }
    }

    @Test
    fun readAheadSavesTheFirstToken() {
        S1.assumeModelPresent()
        val config = EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())
        ExperimentalFlags.enableBenchmark = true
        try {
            Engine(config).use { engine ->
                engine.initialize()
                // Gate 1: without reading ahead the user waits for creating and the first word; with it, the first word.
                for (ahead in listOf(false, true, false, true)) {
                    val started = SystemClock.elapsedRealtime()
                    val conversation = engine.createConversation(chat(ahead))
                    val created = SystemClock.elapsedRealtime() - started
                    conversation.use {
                        val held = it.getTokenCount()
                        val (first, whole) = ask(it, "Which ferry did we choose to come back on? One line.")
                        val info = it.getBenchmarkInfo()
                        val waited = if (ahead) first else created + first
                        S1.report(
                            "read_ahead_$ahead",
                            "create $created ms holding $held tokens, first word $first ms, the user waits $waited ms; " +
                                "the message read ${info.lastPrefillTokenCount} tokens at ${"%.1f".format(info.lastPrefillTokensPerSecond)}/s, " +
                                "whole reply $whole ms",
                        )
                    }
                }

                // Gate 2: a message for another skill, alone, then sent 1 s into a read-ahead that nothing can stop.
                S1.report("read_ahead_other_alone_ms", otherSkill(engine))
                val readAhead = AtomicLong()
                val reading = thread(name = "read-ahead") {
                    val started = SystemClock.elapsedRealtime()
                    engine.createConversation(chat(ahead = true)).use { readAhead.set(SystemClock.elapsedRealtime() - started) }
                }
                SystemClock.sleep(1_000)
                S1.report("read_ahead_other_during_ms", otherSkill(engine))
                reading.join(5 * 60_000L)
                S1.report("read_ahead_during_create_ms", readAhead.get())
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
        }
    }
}
