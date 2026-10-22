package io.github.sandeepvijayarao09.anu.connector.mcp

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.security.InMemoryLedger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class McpTest {
    private val server = MockWebServer()
    private lateinit var client: McpClient

    @Before
    fun setUp() {
        server.start()
        client = McpClient(OkHttpClient(), server.url("/mcp"), clientVersion = "0.0.1")
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun sse(vararg events: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { "event: message\ndata: $it\n\n" })
        .build()

    private val toolsJson = """
        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","ttlMs":60000,"cacheScope":"private","tools":[
          {"name":"search_notes","description":"Search notes <b>fast</b>","inputSchema":{"type":"object","${'$'}schema":"x",
            "properties":{"query":{"type":"string"},"region":{"type":"string","x-mcp-header":"Region"}},"required":["query"]},
            "annotations":{"readOnlyHint":true}},
          {"name":"add_note","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false},
            "annotations":{"destructiveHint":false}},
          {"name":"bad_header","inputSchema":{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string","x-mcp-header":"Tag"}}}}}
        ]}}
    """.trimIndent()

    @Test
    fun `a modern server is asked statelessly, with the version and headers MCP requires`() = runTest {
        server.enqueue(json(toolsJson))
        val list = client.listTools()
        assertEquals(listOf("search_notes", "add_note"), list.tools.map { it.name })
        assertEquals(1, list.rejected.size)
        assertTrue(list.rejected.single(), list.rejected.single().startsWith("bad_header"))
        assertEquals(60_000L, list.ttlMillis)
        assertTrue(list.tools[0].readOnly)
        assertFalse(list.tools[1].destructive)

        val request = server.takeRequest()
        assertEquals("2026-07-28", request.headers["MCP-Protocol-Version"])
        assertEquals("tools/list", request.headers["Mcp-Method"])
        assertEquals("application/json, text/event-stream", request.headers["Accept"])
        assertNull(request.headers["Mcp-Session-Id"])
        val meta = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals("2026-07-28", meta["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("Anu", meta["io.modelcontextprotocol/clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), meta["io.modelcontextprotocol/clientCapabilities"])
    }

    @Test
    fun `a call mirrors the name and marked parameters into headers, and reads an SSE reply past notifications`() = runTest {
        server.enqueue(json(toolsJson))
        val tool = client.listTools().tools.first()
        server.enqueue(
            sse(
                """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""",
                """{"jsonrpc":"2.0","id":2,"result":{"resultType":"complete","content":[{"type":"text","text":"milk, eggs"},{"type":"image","data":"x"}],"isError":false}}""",
            ),
        )
        val result = client.callTool(tool, buildJsonObject { put("query", "groceries"); put("region", "東京") })
        assertEquals("milk, eggs\n[image]", result.text)
        assertFalse(result.isError)
        server.takeRequest()
        val call = server.takeRequest()
        assertEquals("tools/call", call.headers["Mcp-Method"])
        assertEquals("search_notes", call.headers["Mcp-Name"])
        assertEquals("=?base64?5p2x5Lqs?=", call.headers["Mcp-Param-Region"])
    }

    @Test
    fun `a 2025 server gets an initialize session, and keeps it`() = runTest {
        server.enqueue(json("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"Bad Request: No valid session ID provided"},"id":null}""", code = 400))
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "application/json").addHeader("Mcp-Session-Id", "s-42")
                .body("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"old"}}}""").build(),
        )
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(sse("""{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{"tools":[]}}"""))

        assertEquals(listOf("echo"), client.listTools().tools.map { it.name })
        client.listTools()

        server.takeRequest() // the modern attempt
        val initialize = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("initialize", initialize["method"]!!.jsonPrimitive.content)
        assertEquals("2025-11-25", initialize["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        val initialized = server.takeRequest()
        assertEquals("s-42", initialized.headers["Mcp-Session-Id"])
        assertTrue(initialized.body!!.utf8().contains("notifications/initialized"))
        repeat(2) {
            val list = server.takeRequest()
            assertEquals("s-42", list.headers["Mcp-Session-Id"])
            assertEquals("2025-11-25", list.headers["MCP-Protocol-Version"])
            assertFalse("no modern _meta in a legacy session", list.body!!.utf8().contains("_meta"))
        }
    }

    @Test
    fun `a modern error names the versions, and the client uses a 2025 one it knows`() = runTest {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"Unsupported protocol version","data":{"supported":["2025-06-18"],"requested":"2026-07-28"}}}""", code = 400))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}"""))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""))
        client.listTools()
        server.takeRequest()
        assertTrue(server.takeRequest().body!!.utf8().contains("\"2025-06-18\""))
    }

    @Test
    fun `a header mismatch from a modern server is an error, not a fallback`() = runTest {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"error":{"code":-32020,"message":"Header mismatch"}}""", code = 400))
        val error = runCatching { client.listTools() }.exceptionOrNull()
        assertTrue(error is McpException)
        assertEquals(-32020, (error as McpException).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server that asks for more input gets a clear refusal`() = runTest {
        server.enqueue(json(toolsJson))
        val tool = client.listTools().tools[1]
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"resultType":"input_required","inputRequests":{}}}"""))
        val error = runCatching { client.callTool(tool, buildJsonObject { put("text", "hi") }) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("more input"))
    }

    @Test
    fun `tools take their risk from annotations, check arguments first, and ledger every call`() = runTest {
        server.enqueue(json(toolsJson))
        val infos = client.listTools().tools
        val ledger = InMemoryLedger()
        val notes = McpServer("s1", "Notes", server.url("/mcp").toString(), server.hostName)
        val search = McpTool(notes, infos[0], "mcp_notes_search_notes", client, ledger, clock = { Instant.EPOCH })
        val add = McpTool(notes, infos[1], "mcp_notes_add_note", client, ledger)

        assertEquals(RiskTier.READ, search.risk(buildJsonObject { put("query", "x") }))
        assertEquals(RiskTier.WRITE_EXTERNAL, add.risk(buildJsonObject { put("text", "x") }))
        assertEquals(server.hostName, search.sendsTo(JsonObject(emptyMap())))
        assertTrue(runCatching { search.risk(JsonObject(emptyMap())) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { search.risk(buildJsonObject { put("query", 3) }) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue("unknown fields are refused when the schema forbids them", runCatching { add.risk(buildJsonObject { put("text", "x"); put("to", "y") }) }.isFailure)
        assertTrue(search.description, search.description.startsWith("Notes (a service the user added): Search notes"))
        assertFalse("server markup never reaches the prompt", search.description.contains("<b>"))
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                    put("region", buildJsonObject { put("type", "string") })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("query"))))
            },
            search.spec().parameters,
        )

        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"resultType":"complete","content":[{"type":"text","text":"${"a".repeat(2_500)}"}]}}"""))
        val output = search.invoke(buildJsonObject { put("query", "groceries") }, ToolContext("run", "key"))
        assertTrue(output["text"]!!.jsonPrimitive.content.endsWith("… (cut)"))
        val entry = ledger.entries.value.single()
        assertEquals(server.hostName, entry.destination)
        assertEquals("MCP Notes: search_notes", entry.purpose)

        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"resultType":"complete","content":[{"type":"text","text":"note too long"}],"isError":true}}"""))
        val failure = runCatching { add.invoke(buildJsonObject { put("text", "x") }, ToolContext("run", "key2")) }.exceptionOrNull()
        assertEquals("Notes said: note too long", failure?.message)
    }

    @Test
    fun `servers are offered when named, their tools get unique names, and a few are offered at a time`() = runTest {
        val many = (1..8).joinToString(",") { """{"name":"tool-$it","description":"does thing $it","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}""" }
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"tools":[$many,{"name":"find groceries","description":"find groceries in notes","inputSchema":{"type":"object"}}]}}"""))
        val registry = McpRegistry({ client }, InMemoryLedger())
        registry.setServers(listOf(McpServer("s1", "My Notes", server.url("/mcp").toString(), server.hostName)))

        assertEquals(emptyList<String>(), registry.toolsFor(listOf("what's in my notebook?")))
        assertEquals(0, server.requestCount)
        val offered = registry.toolsFor(listOf("Find groceries in my notes", "Ask My Notes please"))
        assertEquals(McpRegistry.MAX_OFFERED, offered.size)
        assertTrue(offered.toString(), "mcp_my_notes_find_groceries" in offered)
        assertTrue(offered.all { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) })
        assertEquals(9, registry.tools().size)
        // By name, and the same list for a follow-up, so the on-device conversation is kept (ADR-098).
        assertEquals(offered.sorted(), offered)
        assertEquals(offered, registry.toolsFor(listOf("Find groceries in my notes", "Ask My Notes please", "my notes: does thing 8?")))
        // Cached for the server's TTL: asking again doesn't refetch.
        registry.toolsFor(listOf("my notes again"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `names and headers follow MCP's rules`() {
        assertEquals("my_notes", McpRegistry.slug("My Notes!", 16))
        assertEquals("notes", McpRegistry.slug("42 notes", 16))
        assertTrue(McpRegistry.mentioned("Notes", "add this to my NOTES please"))
        assertFalse(McpRegistry.mentioned("Notes", "my notebook"))
        assertTrue(McpRegistry.mentioned("My Notes", "ask my  notes"))
        // The spec's own examples.
        assertEquals("us-west1", McpSchemas.headerValue("us-west1"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", McpSchemas.headerValue("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpSchemas.headerValue(" padded "))
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", McpSchemas.headerValue("line1\nline2"))
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", McpSchemas.headerValue("=?base64?literal?="))
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"id":{"type":"integer","x-mcp-header":"Id"},"on":{"type":"boolean","x-mcp-header":"On"},"n":{"type":"number","x-mcp-header":"N"}}}""",
        ).jsonObject
        assertTrue(McpSchemas.headerProblem(schema)!!.contains("number"))
        val ok = Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"integer","x-mcp-header":"Id"},"on":{"type":"boolean","x-mcp-header":"On"}}}""").jsonObject
        assertNull(McpSchemas.headerProblem(ok))
        assertEquals(mapOf("Mcp-Param-Id" to "42", "Mcp-Param-On" to "true"), McpSchemas.paramHeaders(ok, buildJsonObject { put("id", 42); put("on", true) }))
        val twice = Json.parseToJsonElement("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"X"},"b":{"type":"string","x-mcp-header":"x"}}}""").jsonObject
        assertTrue(McpSchemas.headerProblem(twice)!!.contains("twice"))
    }
}
