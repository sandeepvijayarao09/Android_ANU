package io.github.sandeepvijayarao09.anu.connector.gmail

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.security.InMemoryLedger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

class GmailTest {

    private val server = MockWebServer()
    private val ledger = InMemoryLedger()
    private val tokens = FakeTokens()
    private lateinit var gmail: GmailClient

    private class FakeTokens : AccessTokens {
        var current = "tok-1"
        val invalidated = mutableListOf<String>()
        override suspend fun token(scopes: Set<String>) = current
        override suspend fun invalidate(token: String) {
            invalidated += token
            current = "tok-2"
        }
    }

    @Before
    fun setUp() {
        server.start()
        gmail = GmailClient(testGmailHttpClient(server.hostName), tokens, ledger, baseUrl = server.url("/gmail/v1/users/me/"))
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `search lists messages with their headers, and ledgers the query`() = runBlocking {
        server.enqueue(json("""{"messages":[{"id":"m1","threadId":"t1abcdef"}]}"""))
        server.enqueue(json("""{"id":"m1","threadId":"t1abcdef","snippet":"Can we meet &amp; talk?","payload":{"headers":[
            {"name":"From","value":"Maya <maya@example.com>"},{"name":"Subject","value":"Review"},{"name":"Date","value":"Fri, 25 Sep 2026 09:00:00 -0700"}]}}"""))
        val found = gmail.search("from:maya", 5)
        assertEquals(listOf(MessageSummary("m1", "t1abcdef", "Maya <maya@example.com>", "Review", "Fri, 25 Sep 2026 09:00:00 -0700", "Can we meet & talk?")), found)

        val list = server.takeRequest()
        assertEquals("Bearer tok-1", list.headers["Authorization"])
        assertTrue(list.target, list.target.contains("q=from%3Amaya"))
        assertEquals(listOf("email search"), ledger.entries.value.map { it.purpose })
    }

    @Test
    fun `threads prefer the plain text part and fall back to readable html`() = runBlocking {
        server.enqueue(json("""{"id":"t1abcdef","messages":[
          {"id":"a","payload":{"mimeType":"multipart/alternative","headers":[{"name":"Subject","value":"Plan"},{"name":"Message-ID","value":"<x1@mail>"}],
            "parts":[{"mimeType":"text/html","body":{"data":"${b64("<p>html</p>")}"}},{"mimeType":"text/plain","body":{"data":"${b64("Plain body")}"}}]}},
          {"id":"b","payload":{"mimeType":"text/html","headers":[],"body":{"data":"${b64("<div>Hi&nbsp;there<br>Line two</div><script>evil()</script>")}"}}}
        ]}"""))
        val thread = gmail.thread("t1abcdef")
        assertEquals("Plan", thread.subject)
        assertEquals("Plain body", thread.messages[0].text)
        assertEquals("<x1@mail>", thread.messages[0].messageId)
        assertEquals("Hi there\nLine two", thread.messages[1].text)
    }

    @Test
    fun `a refused token is replaced once, then the call is retried`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(json("""{"messages":[]}"""))
        gmail.search("x", 1)
        assertEquals(listOf("tok-1"), tokens.invalidated)
        server.takeRequest()
        assertEquals("Bearer tok-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `api errors surface as GmailException`() {
        server.enqueue(MockResponse.Builder().code(403).body("insufficient scope").build())
        val error = assertThrows(GmailException::class.java) { runBlocking { gmail.search("x", 1) } }
        assertEquals(403, error.code)
    }

    @Test
    fun `the token is attached only for the Gmail host`() {
        val client = testGmailHttpClient("gmail.test.invalid")
        val request = Request.Builder().url(server.url("/steal")).tag(BearerToken::class.java, BearerToken("tok-1")).build()
        assertThrows(IOException::class.java) { client.newCall(request).execute() }
        assertEquals(0, server.requestCount)
        assertFalse(BearerToken("tok-1").toString().contains("tok-1"))
    }

    @Test
    fun `messages are built from fields, with encoded subjects and no header injection`() {
        val raw = Mime.build(OutgoingEmail(listOf("maya@example.com"), subject = "Café plan", body = "See you at 3"))
        assertTrue(raw, raw.contains("To: maya@example.com\r\n"))
        assertTrue(raw, raw.contains("Subject: =?UTF-8?B?"))
        assertTrue(raw, raw.contains("Content-Transfer-Encoding: base64\r\n"))
        assertEquals("See you at 3", String(Base64.getMimeDecoder().decode(raw.substringAfter("\r\n\r\n").trim())))
        assertThrows(IllegalArgumentException::class.java) {
            Mime.build(OutgoingEmail(listOf("maya@example.com"), subject = "Hi\r\nBcc: attacker@evil.example", body = "x"))
        }
    }

    @Test
    fun `email input is validated before anyone is asked to approve it`() {
        assertThrows(IllegalArgumentException::class.java) { EmailInput(listOf("not-an-address"), "Hi", "Body") }
        assertThrows(IllegalArgumentException::class.java) { EmailInput(listOf("a@example.com"), "Hi\nBcc: x@example.com", "Body") }
        assertThrows(IllegalArgumentException::class.java) { EmailInput(List(21) { "p$it@example.com" }, "Hi", "Body") }
        assertThrows(IllegalArgumentException::class.java) { EmailInput(listOf("a@example.com"), "Hi", " ") }
    }

    @Test
    fun `sending is external and replies thread onto the last message`() = runBlocking {
        val send = SendEmailTool(gmail)
        val input = EmailInput(listOf("maya@example.com"), subject = "", body = "Thursday works.", replyToThreadId = "t1abcdef")
        assertEquals(RiskTier.WRITE_EXTERNAL, send.risk(input))
        assertEquals(RiskTier.WRITE_PRIVATE, CreateDraftTool(gmail).risk(input))
        assertEquals("Send an email to maya@example.com, as a reply", send.describe(input))

        server.enqueue(json("""{"id":"t1abcdef","messages":[{"id":"a","payload":{"mimeType":"text/plain","headers":[
            {"name":"Subject","value":"Design review"},{"name":"Message-ID","value":"<m1@mail.example>"}],"body":{"data":"${b64("When?")}"}}}]}"""))
        server.enqueue(json("""{"id":"sent-1","threadId":"t1abcdef"}"""))
        assertEquals(SentOutput("sent-1"), send.invoke(input, ToolContext("run", "key")))

        server.takeRequest() // the thread lookup
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("t1abcdef", body.getValue("threadId").jsonPrimitive.content)
        val mime = String(Base64.getUrlDecoder().decode(body.getValue("raw").jsonPrimitive.content))
        assertTrue(mime, mime.contains("Subject: Re: Design review\r\n"))
        assertTrue(mime, mime.contains("In-Reply-To: <m1@mail.example>\r\n"))
        assertEquals(listOf("email send"), ledger.entries.value.map { it.purpose })
    }
}
