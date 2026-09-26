package io.github.sandeepvijayarao09.anu.connector.shopping

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.security.InMemoryLedger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class ShoppingTest {
    private val server = MockWebServer()
    private lateinit var client: UcpClient
    private lateinit var base: String

    @Before
    fun setUp() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        client = UcpClient(OkHttpClient(), "https://anu.invalid/ucp/profile.json", allowPlainHttp = { true })
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val profile = """
        {"ucp":{"version":"2026-08-25",
          "services":{"dev.ucp.shopping":[{"version":"2026-08-25","transport":"mcp","endpoint":"/mcp"},{"version":"2026-08-25","transport":"rest","endpoint":"/ucp/v1"}]},
          "capabilities":{"dev.ucp.shopping.catalog.search":[{"version":"2026-08-25"}],"dev.ucp.shopping.checkout":[{"version":"2026-08-25"}]},
          "payment_handlers":{}}}
    """.trimIndent()

    private val products = """
        {"ucp":{"version":"2026-08-25"},"products":[
          {"id":"prod_1","title":"Blue Runner Pro","description":{"plain":"Light trail shoe"},"url":"https://shoes.example.com/p/1",
           "price_range":{"min":{"amount":12000,"currency":"USD"},"max":{"amount":12000,"currency":"USD"}},
           "variants":[{"id":"prod_1_10","title":"Size 10","price":{"amount":12000,"currency":"USD"},"availability":{"available":true}}]},
          {"id":"prod_2","title":"Gold Racer","price_range":{"min":{"amount":25000,"currency":"USD"},"max":{"amount":25000,"currency":"USD"}}}
        ]}
    """.trimIndent()

    private suspend fun store(): Store {
        server.enqueue(json(profile))
        return client.discover(base).also { server.takeRequest() }
    }

    @Test
    fun `discovery reads the profile and finds the REST endpoint`() = runTest {
        server.enqueue(json(profile))
        val store = client.discover(base)
        assertEquals("/.well-known/ucp", server.takeRequest().target)
        assertEquals("$base/ucp/v1", store.endpoint)
        assertTrue(store.canSearch && store.canCheckout)
        assertEquals("2026-08-25", store.version)

        server.enqueue(json("""{"ucp":{"version":"2026-08-25","services":[{"transport":"rest","endpoint":"/api"}],"capabilities":{"dev.ucp.shopping.order":[]}}}"""))
        assertTrue(runCatching { client.discover(base) }.exceptionOrNull()?.message.orEmpty().contains("neither"))
        server.enqueue(json("""{"ucp":{"services":{"dev.ucp.shopping":[{"transport":"a2a","endpoint":"/a2a"}]}}}"""))
        assertTrue(runCatching { client.discover(base) }.exceptionOrNull()?.message.orEmpty().contains("REST"))

        val strict = UcpClient(OkHttpClient(), "https://anu.invalid/ucp/profile.json")
        assertTrue(runCatching { strict.discover(base) }.exceptionOrNull()?.message.orEmpty().contains("https"))
    }

    @Test
    fun `search sends UCP's headers and context, and reads products`() = runTest {
        val store = store()
        server.enqueue(json(products))
        val found = client.search(store, "trail shoes", maxPrice = 15000, limit = 5, country = "US", language = "en")
        assertEquals(listOf("Blue Runner Pro", "Gold Racer"), found.map { it.title })
        assertEquals("$120.00", found.first().minPrice!!.format())
        assertEquals(true, found.first().variants.single().available)

        val request = server.takeRequest()
        assertEquals("/ucp/v1/catalog/search", request.target)
        assertEquals("profile=\"https://anu.invalid/ucp/profile.json\"", request.headers["UCP-Agent"])
        assertTrue(request.headers["Request-Id"]!!.isNotBlank())
        val body = request.body!!.utf8()
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        assertEquals("sha-256=:$digest:", request.headers["Content-Digest"])
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("trail shoes", json["query"]!!.jsonPrimitive.content)
        assertEquals("US", json["context"]!!.jsonObject["address_country"]!!.jsonPrimitive.content)
        assertEquals(15000L, json["filters"]!!.jsonObject["price"]!!.jsonObject["max"]!!.jsonPrimitive.long)
        assertEquals(5, json["pagination"]!!.jsonObject["limit"]!!.jsonPrimitive.int)

        server.enqueue(json("""{"ucp":{"version":"2026-08-25","status":"error"},"messages":[{"type":"error","content":"Search is down"}]}"""))
        assertEquals("The store answered: Search is down", runCatching { client.search(store, "x", null, 5, "US", "en") }.exceptionOrNull()?.message)
    }

    @Test
    fun `shop_search searches the named store, filters by price, and ledgers the query`() = runTest {
        val store = store()
        val ledger = InMemoryLedger()
        val seen = SeenProducts()
        val tool = ShopSearchTool({ listOf(store) }, client, seen, ledger, locale = { "US" to "en" })
        server.enqueue(json(products))
        val input = ShopSearchInput("trail shoes", store = store.name, max_price = 150.0)
        assertEquals(RiskTier.READ, tool.risk(input))
        assertEquals(store.endpointHost, tool.sendsTo(input))
        val output = tool.invoke(input, ToolContext("run", "k"))
        assertEquals(listOf("prod_1"), output.results.map { it.product_id })
        assertEquals("prod_1_10", output.results.single().variants.single().variant_id)
        assertEquals("Store search: ${store.name}", ledger.entries.value.single().purpose)
        assertEquals("Blue Runner Pro, Size 10", seen.find(store, "prod_1_10")!!.title)

        val unknown = runCatching { tool.risk(ShopSearchInput("x", store = "megamart")) }.exceptionOrNull()
        assertTrue(unknown?.message.orEmpty(), unknown is IllegalArgumentException && unknown.message!!.contains(store.name))
        val none = ShopSearchTool({ emptyList() }, client, seen, ledger).invoke(ShopSearchInput("x"), ToolContext("run", "k2"))
        assertTrue(none.notes.single().contains("Settings"))
    }

    @Test
    fun `shop_checkout is approved with the exact items and estimate, and hands the user to the store`() = runTest {
        val store = store()
        val ledger = InMemoryLedger()
        val seen = SeenProducts()
        server.enqueue(json(products))
        client.search(store, "shoes", null, 5, "US", "en").forEach { seen.remember(store, it) }
        server.takeRequest()
        val tool = ShopCheckoutTool({ listOf(store) }, client, seen, ledger, linkAllowed = { it.startsWith("https://") })
        val input = ShopCheckoutInput(store.name, listOf(CheckoutItemInput("prod_1_10", 2)))

        assertEquals(RiskTier.WRITE_EXTERNAL, tool.risk(input))
        assertEquals(
            "Start a checkout at ${store.name}: 2 × Blue Runner Pro, Size 10 ($120.00 each). About $240.00 before tax and shipping. You'll review and pay on the store's site.",
            tool.describe(input),
        )
        assertTrue(runCatching { tool.risk(input.copy(items = listOf(CheckoutItemInput("prod_1_10", 0)))) }.isFailure)
        assertTrue(runCatching { tool.risk(input.copy(items = emptyList())) }.isFailure)

        val session = """
            {"ucp":{"version":"2026-08-25"},"id":"chk_1","status":"incomplete","currency":"USD",
             "line_items":[{"id":"li_1","item":{"id":"prod_1_10","title":"Blue Runner Pro, Size 10","price":12000},"quantity":2}],
             "totals":[{"type":"subtotal","amount":24000},{"type":"total","amount":24000}],
             "messages":[{"type":"error","code":"missing","content":"Add a shipping address"}],
             "continue_url":"https://shoes.example.com/checkout/chk_1"}
        """.trimIndent()
        server.enqueue(json(session, code = 201))
        server.enqueue(json(session.replace("https://shoes.example.com", "http://evil.example"), code = 201))
        val output = tool.invoke(input, ToolContext("run", "same-key"))
        assertEquals("incomplete", output.status)
        assertEquals("$240.00", output.total)
        assertEquals(listOf("2 × Blue Runner Pro, Size 10 ($120.00)"), output.items)
        assertEquals("https://shoes.example.com/checkout/chk_1", output.open_url)
        assertEquals("Finish at shoes.example.com", output.open_label)
        assertEquals(listOf("Add a shipping address"), output.messages)

        val first = server.takeRequest()
        assertEquals("/ucp/v1/checkout-sessions", first.target)
        val lines = Json.parseToJsonElement(first.body!!.utf8()).jsonObject["line_items"]!!.jsonArray
        assertEquals("prod_1_10", lines.single().jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Checkout at ${store.name}", ledger.entries.value.single().purpose)

        // A retry of the same call carries the same Idempotency-Key, and a plain-http link is never offered.
        val again = tool.invoke(input, ToolContext("run", "same-key"))
        assertEquals(first.headers["Idempotency-Key"], server.takeRequest().headers["Idempotency-Key"])
        assertNull(again.open_url)
        assertTrue(again.next_step.contains("gave no link"))
    }
}
