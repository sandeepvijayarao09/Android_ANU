package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.JsonSchemas
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** S1: the [LiteRtLmBrain] contract against a real Gemma 4 model (streaming, tools, structured output, cancel). */
@RunWith(AndroidJUnit4::class)
class LiteRtLmBrainTest {

    @Serializable
    @Description("City to look up")
    data class WeatherQuery(@Description("City name, e.g. Paris") val city: String)

    @Serializable
    enum class Tier { BASE, PRO, CLOUD }

    @Serializable
    data class RouteGuess(val tier: Tier, val reason: String)

    private val weatherTool = ToolSpec.of("weather_get_current", "Get the current weather for a city", WeatherQuery.serializer())

    @Test
    fun streamsTextThenUsageThenFinished() = runBlocking {
        val started = System.nanoTime()
        var firstTokenNanos = 0L
        val events = brain.stream(
            BrainRequest(transcript = listOf(ChatMessage.User("Reply with just the word: pong")), sampling = Sampling.Precise),
        ).onEach { if (it is BrainEvent.TextDelta && firstTokenNanos == 0L) firstTokenNanos = System.nanoTime() }.toList()

        S1.report("brain_ttft_ms", (firstTokenNanos - started) / 1_000_000)
        val text = events.filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text }
        assertTrue("reply was: $text", text.contains("pong", ignoreCase = true))
        assertTrue(events[events.lastIndex - 1] is BrainEvent.Usage)
        assertEquals(BrainEvent.Finished(StopReason.COMPLETE), events.last())
    }

    @Test
    fun reportsToolCallsWithoutRunningThem() = runBlocking {
        val system = "You are Anu. Use tools when they help."
        val first = listOf(ChatMessage.User("What's the weather in Paris right now?"))
        val events = brain.stream(
            BrainRequest(system = system, transcript = first, tools = listOf(weatherTool), sampling = Sampling.Precise),
        ).toList()

        val calls = events.filterIsInstance<BrainEvent.ToolCallRequested>().map { it.call }
        S1.report("tool_calls", calls.joinToString { "${it.name}${it.arguments}" }.ifEmpty { "none" })
        assertEquals(1, calls.size)
        assertEquals("weather_get_current", calls.single().name)
        assertTrue(calls.single().arguments.getValue("city").jsonPrimitive.content.contains("Paris", ignoreCase = true))
        assertEquals(BrainEvent.Finished(StopReason.TOOL_CALLS), events.last())

        // Send the result back. The conversation is reused because the transcript extends what it saw (ADR-018).
        val reply = events.filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text }
        val second = first + ChatMessage.Model(reply, calls) + ChatMessage.Tool(
            listOf(ToolResult("weather_get_current", buildJsonObject { put("temp_c", 18); put("sky", "cloudy") })),
        )
        val answer = brain.stream(
            BrainRequest(system = system, transcript = second, tools = listOf(weatherTool), sampling = Sampling.Precise),
        ).toList().filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text }
        S1.report("tool_answer", answer.replace('\n', ' ').take(160))
        assertTrue("answer was: $answer", answer.contains("18"))
    }

    @Test
    fun structuredOutputMatchesSchema() = runBlocking {
        val events = brain.stream(
            BrainRequest(
                system = "Classify how hard the user's request is. BASE = trivial, PRO = multi-step, CLOUD = needs the web.",
                transcript = listOf(ChatMessage.User("Find a time next week for me, Maya and Raj, then email them an invite.")),
                responseSchema = JsonSchemas.of(RouteGuess.serializer()),
                sampling = Sampling.Precise,
            ),
        ).toList()
        val json = events.filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text }
        S1.report("structured_output", json.replace('\n', ' ').take(160))
        Json.decodeFromString(RouteGuess.serializer(), json) // throws if the output doesn't match the schema
        Unit
    }

    /**
     * A conversation built from a saved transcript (ADR-018) holds the model's earlier answers. LiteRT-LM 0.18 sends
     * them with the role "assistant" instead of "model", so this checks the model still reads them as its own turns.
     */
    @Test
    fun readsEarlierTurnsFromTheTranscript() = runBlocking {
        val transcript = listOf(
            ChatMessage.User("I'm planning a picnic on Saturday."),
            ChatMessage.Model("Nice! I'll suggest the east cliffs, since they're sheltered from the wind."),
            ChatMessage.User("Which place did you suggest? Reply with just the place."),
        )
        val text = brain.stream(BrainRequest(transcript = transcript, sampling = Sampling.Precise))
            .toList().filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text }
        assertTrue("reply was: $text", text.contains("cliff", ignoreCase = true))
    }

    /**
     * A one-message request with another system prompt, such as Writing or Test speed, runs beside the chat's
     * conversation (ADR-097), so the chat's next turn reads only its new message, not the whole chat again.
     */
    @Test
    fun aOneOffRequestKeepsTheChatsConversation() = runBlocking {
        val chat = mutableListOf<ChatMessage>(ChatMessage.User(LONG_MESSAGE))
        chat += ChatMessage.Model(answer(BrainRequest(transcript = chat.toList(), sampling = Sampling.Precise, maxOutputTokens = 32)).first)
        val oneOpen = residentKb()
        answer(BrainRequest(system = "Rewrite the text in a friendly tone.", transcript = listOf(ChatMessage.User("see you at noon")), sampling = Sampling.Precise, maxOutputTokens = 16))
        S1.report("second_conversation_rss_kb", residentKb() - oneOpen)
        chat += ChatMessage.User("Reply with just the word: done")
        val (text, usage) = answer(BrainRequest(transcript = chat.toList(), sampling = Sampling.Precise))
        val read = usage.inputTokens ?: Int.MAX_VALUE
        S1.report("follow_up_read_tokens", read)
        assertTrue("reply was: $text", text.contains("done", ignoreCase = true))
        assertTrue("the follow-up read $read tokens, so the chat was read again", read < 60)
    }

    /** The process's resident memory now, from /proc. */
    private fun residentKb(): Long = java.io.File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong()

    private suspend fun answer(request: BrainRequest): Pair<String, BrainEvent.Usage> {
        val events = brain.stream(request).toList()
        return events.filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text } to events.filterIsInstance<BrainEvent.Usage>().single()
    }

    @Test
    fun cancelStopsGenerationAndBrainRecovers() = runBlocking {
        val firstToken = CompletableDeferred<Unit>()
        val job = launch {
            brain.stream(
                BrainRequest(transcript = listOf(ChatMessage.User("Write a very long, detailed story about a lighthouse keeper.")), maxOutputTokens = 1024),
            ).collect { if (it is BrainEvent.TextDelta) firstToken.complete(Unit) }
        }
        firstToken.await()
        val cancelStarted = System.nanoTime()
        job.cancelAndJoin()
        val cancelMs = (System.nanoTime() - cancelStarted) / 1_000_000
        S1.report("cancel_ms", cancelMs)

        // After a cancel the conversation is rebuilt from the transcript, and the next request must work.
        val text = brain.stream(
            BrainRequest(transcript = listOf(ChatMessage.User("Reply with just the word: ready")), sampling = Sampling.Precise),
        ).toList().filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text }
        assertTrue("reply was: $text", text.contains("ready", ignoreCase = true))
        assertTrue("I7 target is 500 ms; cancel took $cancelMs ms", cancelMs <= 500)
    }

    companion object {
        /** About 250 tokens, so reading it again would show in the follow-up's count. */
        private val LONG_MESSAGE = "Here are my notes from the week. " + List(10) { i ->
            "On day ${i + 1} I walked the harbor loop, had lunch by the old pier, and read on the east cliffs."
        }.joinToString(" ") + " Say only: noted."

        private lateinit var holder: EngineHolder
        private lateinit var brain: LiteRtLmBrain

        @JvmStatic
        @BeforeClass
        fun setUp() {
            S1.assumeModelPresent()
            holder = EngineHolder(EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = S1.maxNumTokens, cacheDir = S1.cacheDir()), S1.options())
            brain = LiteRtLmBrain(
                id = BrainId.PRO,
                capabilities = BrainCapabilities(BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true),
                engines = holder,
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
