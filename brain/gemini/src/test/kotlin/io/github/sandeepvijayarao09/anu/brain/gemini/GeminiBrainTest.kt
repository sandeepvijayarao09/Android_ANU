package io.github.sandeepvijayarao09.anu.brain.gemini

import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.NOTE_HEADER
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import io.github.sandeepvijayarao09.anu.core.model.ToolSpec
import io.github.sandeepvijayarao09.anu.core.security.EgressGate
import io.github.sandeepvijayarao09.anu.core.security.InMemoryLedger
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.security.SecretUse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiBrainTest {

    private val server = MockWebServer()
    private val ledger = InMemoryLedger()
    private val tags = mutableListOf<SecretUse?>()
    private lateinit var brain: GeminiBrain

    @Before
    fun setUp() {
        server.start()
        // Stands in for the vault's injector: records the tag instead of attaching a key.
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            tags += chain.request().tag(SecretUse::class.java)
            chain.proceed(chain.request())
        }.build()
        brain = GeminiBrain(
            id = BrainId.CLOUD_FLASH,
            model = "gemini-flash-latest",
            apiKey = SecretRef("11111111-2222-3333-4444-555555555555"),
            http = http,
            egress = EgressGate(ledger),
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() = server.close()

    private fun sse(vararg chunks: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body(chunks.joinToString("") { "data: $it\r\n\r\n" })
        .build()

    private fun text(t: String, total: Int) =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":${Json.encodeToString(String.serializer(), t)}}]}}],""" +
            """"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":${total - 10},"totalTokenCount":$total}}"""

    private val hello = BrainRequest(transcript = listOf(ChatMessage.User("Say hello to maya@example.com")), dataClasses = setOf(DataClass.PERSONAL))

    @Test
    fun `streams text, then usage once with the final totals, then finished`() = runBlocking {
        server.enqueue(sse(text("Hello ", 12), text("[EMAIL_1]!", 15)))
        val events = brain.stream(hello).toList()
        assertEquals(
            listOf(
                BrainEvent.TextDelta("Hello "),
                BrainEvent.TextDelta("maya@example.com!"),
                BrainEvent.Usage(contextTokens = 15, inputTokens = 10, outputTokens = 5),
                BrainEvent.Finished(StopReason.COMPLETE),
            ),
            events,
        )
    }

    @Test
    fun `the request is redacted, ledgered and keyed through the vault, never the URL`() = runBlocking {
        server.enqueue(sse(text("ok", 11)))
        brain.stream(hello).toList()
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-flash-latest:streamGenerateContent?alt=sse", recorded.target)
        val body = recorded.body!!.utf8()
        assertFalse(body.contains("maya@example.com"))
        assertTrue(body.contains("[EMAIL_1]"))
        assertEquals(SecretUse(SecretRef("11111111-2222-3333-4444-555555555555"), "x-goog-api-key", GeminiBrain.CALLER), tags.single())
        assertFalse(recorded.target.contains("key="))
        assertEquals(server.hostName, ledger.entries.value.single().destination)
    }

    @Test
    fun `tool calls are reported with restored arguments and their thought signature`() = runBlocking {
        server.enqueue(
            sse(
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"email_send","args":{"to":"[EMAIL_1]"}},"thoughtSignature":"sig-abc"}]}}]}""",
            ),
        )
        val events = brain.stream(hello).toList()
        val call = events.filterIsInstance<BrainEvent.ToolCallRequested>().single().call
        assertEquals(ToolCall("email_send", buildJsonObject { put("to", "maya@example.com") }, signature = "sig-abc"), call)
        assertEquals(BrainEvent.Finished(StopReason.TOOL_CALLS), events.last())
    }

    @Test
    fun `transcripts are sent in Gemini's shape, echoing thought signatures`() = runBlocking {
        server.enqueue(sse(text("done", 20)))
        val tool = ToolSpec("email_send", "Send an email", buildJsonObject { put("type", "object") })
        brain.stream(
            BrainRequest(
                system = "You are Anu.",
                transcript = listOf(
                    ChatMessage.User("Email Maya"),
                    ChatMessage.Model("", listOf(ToolCall("email_send", JsonObject(emptyMap()), signature = "sig-abc"))),
                    ChatMessage.Tool(listOf(ToolResult("email_send", buildJsonObject { put("status", "sent") }))),
                ),
                tools = listOf(tool),
            ),
        ).toList()
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val contents = body.getValue("contents").jsonArray
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        val modelPart = contents[1].jsonObject.getValue("parts").jsonArray.single().jsonObject
        assertEquals("sig-abc", modelPart.getValue("thoughtSignature").jsonPrimitive.content)
        val response = contents[2].jsonObject.getValue("parts").jsonArray.single().jsonObject.getValue("functionResponse").jsonObject
        assertEquals(buildJsonObject { put("status", "sent") }, response.getValue("response"))
        val declaration = body.getValue("tools").jsonArray.single().jsonObject.getValue("functionDeclarations").jsonArray.single().jsonObject
        assertEquals(buildJsonObject { put("type", "object") }, declaration.getValue("parametersJsonSchema"))
    }

    @Test
    fun `the newest user message carries the time it was sent`() = runBlocking {
        server.enqueue(sse(text("ok", 5)))
        brain.stream(
            BrainRequest(
                transcript = listOf(ChatMessage.User("Hi"), ChatMessage.Model("Hello!"), ChatMessage.User("What time is it?")),
                sentAt = "Fri 2026-09-25 14:46",
            ),
        ).toList()
        val contents = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.getValue("contents").jsonArray
        val texts = contents.map { it.jsonObject.getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content }
        assertEquals(listOf("Hi", "Hello!", "[Fri 2026-09-25 14:46] What time is it?"), texts)
    }

    @Test
    fun `a repair note escalated to the cloud goes with the newest message, not the system instruction`() = runBlocking {
        server.enqueue(sse(text("ok", 5)))
        brain.stream(
            BrainRequest(system = "You are Anu.", transcript = listOf(ChatMessage.User("I'm vegetarian")), note = "Don't say \"noted\"."),
        ).toList()
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val text = body.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertEquals("I'm vegetarian\n\n$NOTE_HEADER Don't say \"noted\".", text)
        assertTrue("noted" !in body.getValue("systemInstruction").toString())
    }

    @Test
    fun `memory never leaves the phone`() = runBlocking {
        server.enqueue(sse(text("ok", 5)))
        brain.stream(
            BrainRequest(
                system = "You are Anu.",
                transcript = listOf(ChatMessage.User("Book lunch")),
                memory = "What you know about the user:\n- manager: Maya Chen",
                relevant = "Facts from memory about this message:\n- Maya Chen · birthday: 12 March",
            ),
        ).toList()
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, "Maya Chen" !in body && "What you know" !in body && "Facts from memory" !in body)
        assertTrue(body, "Book lunch" in body)
    }

    @Test
    fun `only the facts the user shared go, redacted, with the newest message`() = runBlocking {
        server.enqueue(sse(text("ok", 5)))
        brain.stream(
            BrainRequest(
                system = "You are Anu.",
                transcript = listOf(ChatMessage.User("Gift ideas for my manager?")),
                memory = "What you know about the user:\n- diet: vegan",
                relevant = "Facts from memory about this message:\n- sister: Priya",
                sharedFacts = listOf("manager: Maya Chen", "Maya Chen · email: maya@example.com"),
            ),
        ).toList()
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, "manager: Maya Chen" in body && "Gift ideas for my manager?" in body)
        assertTrue(body, "maya@example.com" !in body)
        assertTrue(body, "vegan" !in body && "Priya" !in body)
        assertEquals("chat · 2 facts from memory", ledger.entries.value.last().purpose)
    }

    @Test
    fun `http errors surface as GeminiException`() {
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"message":"quota"}}""").build())
        val error = assertThrows(GeminiException::class.java) { runBlocking { brain.stream(hello).toList() } }
        assertEquals(429, error.code)
    }

    @Test
    fun `cancelling stops a slow stream promptly`() = runBlocking {
        val chunks = (1..50).map { text("word ", 11 + it) }.toTypedArray()
        server.enqueue(sse(*chunks).newBuilder().throttleBody(64, 200, TimeUnit.MILLISECONDS).build())
        val started = System.nanoTime()
        brain.stream(hello).first { it is BrainEvent.TextDelta }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000) // a full read would take about 60 s
    }
}
