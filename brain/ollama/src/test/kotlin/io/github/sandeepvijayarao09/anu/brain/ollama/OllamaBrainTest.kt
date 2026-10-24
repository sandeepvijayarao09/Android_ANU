package io.github.sandeepvijayarao09.anu.brain.ollama

import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Photo
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

class OllamaBrainTest {

    private val server = MockWebServer()
    private val ledger = InMemoryLedger()
    private val tags = mutableListOf<SecretUse?>()
    private val token = SecretRef("11111111-2222-3333-4444-555555555555")
    private lateinit var brain: OllamaBrain

    @Before
    fun setUp() {
        server.start()
        // Stands in for the vault's injector: records the tag instead of attaching the token.
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            tags += chain.request().tag(SecretUse::class.java)
            chain.proceed(chain.request())
        }.build()
        brain = OllamaBrain(server.url("/"), token, http, EgressGate(ledger))
    }

    @After
    fun tearDown() = server.close()

    private fun ndjson(vararg chunks: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/x-ndjson")
        .body(chunks.joinToString("") { "$it\n" })
        .build()

    private fun text(t: String) = """{"message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), t)}},"done":false}"""

    private val done =
        """{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":10,"eval_count":5,"prompt_eval_duration":200000000,"eval_duration":300000000}"""

    private val hello = BrainRequest(transcript = listOf(ChatMessage.User("Say hello to maya@example.com")), dataClasses = setOf(DataClass.PERSONAL))

    @Test
    fun `streams text, then the Mac's totals and timings once, then finished`() = runBlocking {
        server.enqueue(ndjson(text("Hello "), text("[EMAIL_1]!"), done))
        val events = brain.stream(hello).toList()
        assertEquals(
            listOf(
                BrainEvent.TextDelta("Hello "),
                BrainEvent.TextDelta("maya@example.com!"),
                BrainEvent.Usage(contextTokens = 15, inputTokens = 10, outputTokens = 5, backend = "MAC", readMs = 200, writeMs = 300, counted = true),
                BrainEvent.Finished(StopReason.COMPLETE),
            ),
            events,
        )
    }

    @Test
    fun `it is off the phone, so the request is redacted, ledgered to the Mac and carries the token by the vault`() = runBlocking {
        assertEquals(BrainLocation.CLOUD, brain.capabilities.location)
        server.enqueue(ndjson(text("ok"), done))
        brain.stream(hello).toList()
        val recorded = server.takeRequest()
        assertEquals("/api/chat", recorded.target)
        val body = recorded.body!!.utf8()
        assertFalse(body.contains("maya@example.com"))
        assertTrue(body.contains("[EMAIL_1]"))
        assertNull(recorded.headers["Authorization"])
        assertEquals(SecretUse(token, "Authorization", OllamaBrain.CALLER, scheme = "Bearer"), tags.single())
        assertEquals(server.hostName, ledger.entries.value.single().destination)
    }

    @Test
    fun `photos never reach the Mac`() {
        val photo = BrainRequest(transcript = listOf(ChatMessage.User("What is this?", photos = listOf(Photo(1, byteArrayOf(1))))))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { brain.stream(photo).toList() } }
        assertTrue(ledger.entries.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tool calls are reported with restored arguments`() = runBlocking {
        server.enqueue(
            ndjson(
                """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"email_send","arguments":{"to":"[EMAIL_1]"}}}]},"done":false}""",
                done,
            ),
        )
        val events = brain.stream(hello).toList()
        val call = events.filterIsInstance<BrainEvent.ToolCallRequested>().single().call
        assertEquals(ToolCall("email_send", buildJsonObject { put("to", "maya@example.com") }), call)
        assertEquals(BrainEvent.Finished(StopReason.TOOL_CALLS), events.last())
    }

    @Test
    fun `transcripts are sent in Ollama's shape, with schemas and sampling`() = runBlocking {
        server.enqueue(ndjson(text("done"), done))
        val tool = ToolSpec("email_send", "Send an email", buildJsonObject { put("type", "object") })
        brain.stream(
            BrainRequest(
                system = "You are Anu.",
                transcript = listOf(
                    ChatMessage.User("Email Maya"),
                    ChatMessage.Model("", listOf(ToolCall("email_send", buildJsonObject { put("to", "Maya") }))),
                    ChatMessage.Tool(listOf(ToolResult("email_send", buildJsonObject { put("status", "sent") }))),
                ),
                tools = listOf(tool),
                responseSchema = buildJsonObject { put("type", "object") },
                maxOutputTokens = 256,
            ),
        ).toList()
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.getValue("role").jsonPrimitive.content })
        val call = messages[2].getValue("tool_calls").jsonArray.single().jsonObject.getValue("function").jsonObject
        assertEquals(buildJsonObject { put("to", "Maya") }, call.getValue("arguments"))
        assertEquals("email_send", messages[3].getValue("tool_name").jsonPrimitive.content)
        assertEquals("""{"status":"sent"}""", messages[3].getValue("content").jsonPrimitive.content)
        val function = body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
        assertEquals(buildJsonObject { put("type", "object") }, function.getValue("parameters"))
        assertEquals(buildJsonObject { put("type", "object") }, body.getValue("format"))
        assertEquals("256", body.getValue("options").jsonObject.getValue("num_predict").jsonPrimitive.content)
        assertEquals("false", body.getValue("think").jsonPrimitive.content) // Ollama would think by default
        assertFalse("model" in body) // the Mac's gateway chooses it
    }

    @Test
    fun `memory never leaves the phone, and only the facts the user shared go, redacted`() = runBlocking {
        server.enqueue(ndjson(text("ok"), done))
        brain.stream(
            BrainRequest(
                system = "You are Anu.",
                transcript = listOf(ChatMessage.User("Gift ideas for my manager?")),
                memory = "What you know about the user:\n- diet: vegan",
                relevant = "Facts from memory about this message:\n- sister: Priya",
                onDeviceNote = "You are Gemma 4 on this phone.",
                sharedFacts = listOf("manager: Maya Chen", "Maya Chen · email: maya@example.com"),
            ),
        ).toList()
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, "manager: Maya Chen" in body && "Gift ideas for my manager?" in body)
        assertTrue(body, "maya@example.com" !in body && "vegan" !in body && "Priya" !in body && "on this phone" !in body)
        assertEquals("chat · 2 facts from memory", ledger.entries.value.last().purpose)
    }

    @Test
    fun `errors surface as MacBrainException`() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"wrong token"}""").build())
        assertEquals(401, assertThrows(MacBrainException::class.java) { runBlocking { brain.stream(hello).toList() } }.code)
        server.enqueue(ndjson("""{"error":"model is loading"}"""))
        assertThrows(MacBrainException::class.java) { runBlocking { brain.stream(hello).toList() } }
    }

    @Test
    fun `cancelling stops a slow stream promptly`() = runBlocking {
        val chunks = (1..50).map { text("word ") }.toTypedArray()
        server.enqueue(ndjson(*chunks, done).newBuilder().throttleBody(64, 200, TimeUnit.MILLISECONDS).build())
        val started = System.nanoTime()
        brain.stream(hello).first { it is BrainEvent.TextDelta }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
    }

    @Test
    fun `a pairing code gives the host, port and pin, and the token apart`() {
        val (pairing, secret) = MacPairing.parse("anu-mac://192.168.0.134:11435?pin=${PIN.uppercase()}&token=${"t".repeat(43)}")!!
        assertEquals(MacPairing("192.168.0.134", 11435, PIN), pairing)
        assertEquals("t".repeat(43), secret)
        assertEquals("https://192.168.0.134:11435/", pairing.baseUrl.toString())
        assertNull(MacPairing.parse("https://192.168.0.134:11435?pin=$PIN&token=${"t".repeat(43)}"))
        assertNull(MacPairing.parse("anu-mac://192.168.0.134:11435?pin=abc&token=${"t".repeat(43)}"))
        assertNull(MacPairing.parse("anu-mac://192.168.0.134:11435?pin=$PIN&token=short"))
    }

    @Test
    fun `only the paired certificate is trusted`() {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(CERT.byteInputStream()) as X509Certificate
        PinnedTrust(PIN).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertThrows(CertificateException::class.java) { PinnedTrust("0".repeat(64)).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { PinnedTrust(PIN).checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
    }

    private companion object {
        /** A throwaway self-signed certificate, made for this test only, and its SHA-256. */
        const val PIN = "febe334944f4c5620c09277795a3e28389977ef88c7716b743f4ada0238e2cd1"
        val CERT = """
            -----BEGIN CERTIFICATE-----
            MIIBfDCCASGgAwIBAgIUDWNcRbMyw7ZvOvtZjmY68fF/iG0wCgYIKoZIzj0EAwIw
            EzERMA8GA1UEAwwIYW51LXRlc3QwHhcNMjYxMDI0MjEwOTMxWhcNMzYxMDIxMjEw
            OTMxWjATMREwDwYDVQQDDAhhbnUtdGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEH
            A0IABJxf2jfSEHwuCbQeaPygtM8qxj5wsB/QX0BOGMOuDG0QgZEt4jKNukby6E8q
            U72Cl0e3g73SozS0b7WYtxTueSejUzBRMB0GA1UdDgQWBBTPwtYEJ1U90wjTDw31
            utGTa58PtjAfBgNVHSMEGDAWgBTPwtYEJ1U90wjTDw31utGTa58PtjAPBgNVHRMB
            Af8EBTADAQH/MAoGCCqGSM49BAMCA0kAMEYCIQCCTVhYu9o4cX9rjxQeOHAJKteh
            tSVfaZE6VI8icpqwUAIhAMIlwpHxYkcnajw3GK/1HGFLAhtdREY0rfdKDcOqWqL8
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
