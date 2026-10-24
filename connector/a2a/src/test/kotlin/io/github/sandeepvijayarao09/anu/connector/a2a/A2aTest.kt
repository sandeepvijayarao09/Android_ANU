package io.github.sandeepvijayarao09.anu.connector.a2a

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.security.InMemoryLedger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aTest {
    private val server = MockWebServer().apply { start() }
    private val client = A2aClient(OkHttpClient())

    @After
    fun tearDown() = server.close()

    private fun body(text: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(text).build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private val card = """{"name":"Home Lab","description":"The user's lab robot and sensors","url":"/a2a","version":"1.0",
        "skills":[{"id":"room","name":"Room status"},{"id":"lights","name":"Lights"}]}"""

    @Test
    fun `a card names the agent, where it answers and its skills`() = runTest {
        server.enqueue(body(card))
        val agent = client.card(server.url("/"))
        assertEquals("Home Lab", agent.name)
        assertEquals(server.url("/a2a").toString(), agent.url)
        assertEquals(listOf("Room status", "Lights"), agent.skills)
        assertEquals("/.well-known/agent-card.json", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an older agent's card comes from agent json`() = runTest {
        server.enqueue(status(404))
        server.enqueue(body(card))
        assertEquals("Home Lab", client.card(server.url("/")).name)
        server.takeRequest()
        assertEquals("/.well-known/agent.json", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a message goes as message-send, and a task's answer is read from its status and artifacts`() = runTest {
        server.enqueue(body(card))
        val agent = client.card(server.url("/"))
        server.takeRequest()
        server.enqueue(
            body("""{"jsonrpc":"2.0","id":"1","result":{"kind":"task","id":"t1","status":{"state":"completed",
                "message":{"kind":"message","role":"agent","parts":[{"kind":"text","text":"It's 21.5 °C."}]}},
                "artifacts":[{"parts":[{"kind":"text","text":"Humidity 41%."},{"kind":"data","data":{}}]}]}}""",
            ),
        )
        assertEquals("It's 21.5 °C.\nHumidity 41%.", client.send(agent, "What's the temperature?"))
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("message/send", sent["method"]!!.jsonPrimitive.content)
        val part = sent["params"]!!.jsonObject["message"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals("What's the temperature?", part["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an agent's error is the tool's failure, in the agent's words, cut short`() = runTest {
        server.enqueue(body(card))
        val agent = client.card(server.url("/"))
        server.enqueue(body("""{"jsonrpc":"2.0","id":"1","error":{"code":-32600,"message":"Robot arm is offline"}}"""))
        val error = runCatching { client.send(agent, "Wave") }.exceptionOrNull()
        assertEquals("Home Lab said: Robot arm is offline", error?.message)
    }

    @Test
    fun `as a tool it always asks, says where the words go, and writes the ledger`() = runTest {
        server.enqueue(body(card))
        val ledger = InMemoryLedger()
        val tool = A2aTool(client.card(server.url("/")), client, ledger)
        server.takeRequest()
        assertEquals("a2a_home_lab", tool.name)
        val ask = AskAgentInput("Turn on the desk lamp")
        assertEquals(RiskTier.WRITE_EXTERNAL, tool.risk(ask))
        assertEquals(server.hostName, tool.sendsTo(ask))
        assertEquals("Ask Home Lab (${server.hostName}): “Turn on the desk lamp”", tool.describe(ask))
        server.enqueue(body("""{"jsonrpc":"2.0","id":"1","result":{"kind":"message","role":"agent","parts":[{"kind":"text","text":"Desk lamp on."}]}}"""))
        assertEquals(AgentAnswer("Home Lab", "Desk lamp on."), tool.invoke(ask, ToolContext("run", "key")))
        assertEquals(server.hostName, ledger.entries.value.single().destination)
        assertTrue(tool.description.contains("Room status"))
    }

    @Test
    fun `the agents list keeps only http addresses, and an agent that doesn't answer is left out with its reason`() = runTest {
        val addresses = mutableListOf<String>()
        val list = object : A2aAgentList {
            override fun addresses() = addresses.toList()
            override fun add(address: String) { addresses += address }
            override fun remove(address: String) { addresses -= address }
        }
        val agents = A2aAgents(list, client, null)
        assertEquals(false, agents.add("not a url"))
        assertTrue(agents.add(server.url("/").toString()))
        assertTrue(agents.add(server.url("/down/").toString()))
        server.enqueue(body(card))
        server.enqueue(status(500))
        server.enqueue(status(500))
        agents.refresh()
        assertEquals(listOf("a2a_home_lab"), agents.tools().map { it.name })
        assertEquals(1, agents.problems.size)
    }
}
