package io.github.sandeepvijayarao09.anu.connector.shopping

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import java.util.Currency
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A store the user added by its address, as its UCP profile describes it (ADR-047). */
data class Store(
    /** What the user typed, normalized: `shoes.example.com`, or a full URL for a local test store. */
    val address: String,
    val name: String,
    /** The REST endpoint from the profile. Searches and checkouts go here. */
    val endpoint: String,
    val capabilities: Set<String>,
    val version: String,
) {
    val endpointHost: String get() = endpoint.toHttpUrlOrNull()?.host ?: endpoint
    val canSearch: Boolean get() = capabilities.any { it == CATALOG_SEARCH || it == CATALOG }
    val canCheckout: Boolean get() = CHECKOUT in capabilities

    companion object {
        const val CATALOG = "dev.ucp.shopping.catalog"
        const val CATALOG_SEARCH = "dev.ucp.shopping.catalog.search"
        const val CHECKOUT = "dev.ucp.shopping.checkout"
    }
}

/** An amount in minor units, such as cents, as UCP sends it. */
data class Money(val amount: Long, val currency: String) {
    fun format(): String {
        val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
        val value = BigDecimal.valueOf(amount, digits).toPlainString()
        return when (currency) {
            "USD" -> "$$value"
            "EUR" -> "€$value"
            "GBP" -> "£$value"
            else -> "$value $currency"
        }
    }
}

data class Variant(val id: String, val title: String?, val price: Money?, val available: Boolean?)

data class Product(
    val id: String,
    val title: String,
    val description: String?,
    val url: String?,
    val minPrice: Money?,
    val maxPrice: Money?,
    val variants: List<Variant>,
)

data class CheckoutLine(val itemId: String, val title: String?, val quantity: Int, val price: Money?)

data class Checkout(
    val id: String,
    val status: String,
    val lines: List<CheckoutLine>,
    val total: Money?,
    val continueUrl: String?,
    val messages: List<String>,
)

class UcpException(message: String) : IOException(message)

/**
 * UCP's REST binding, 2026-08-25 (ADR-047): discovery, catalog search, and creating a checkout session.
 * - Every request says who is asking in `UCP-Agent`, and carries a `Request-Id`.
 * - Creating a checkout carries an `Idempotency-Key`, stable across retries, and a `Content-Digest`.
 * - Anu never completes a checkout or handles payment: the user finishes on the store's own site.
 * - Business errors come back as HTTP 200 with messages, and are reported as errors.
 * - Cancelling the coroutine cancels the HTTP call (I7).
 */
class UcpClient(
    private val http: OkHttpClient,
    /** Anu's platform profile URL, which UCP asks every request to name. */
    private val profileUrl: String,
    /** Plain HTTP is refused, except for local test stores in debug builds. */
    private val allowPlainHttp: (HttpUrl) -> Boolean = { false },
) {
    /** Reads the store's profile at `/.well-known/ucp`, and finds its REST endpoint. */
    suspend fun discover(address: String): Store {
        val base = baseUrl(address)
        val profile = get(base.newBuilder().encodedPath("/.well-known/ucp").build())
        val ucp = profile["ucp"] as? JsonObject ?: throw UcpException("${base.host} has no UCP profile.")
        val version = ucp.string("version") ?: "unknown"
        val endpoint = restEndpoints(ucp["services"]).firstNotNullOfOrNull { raw -> base.resolve(raw)?.takeIf(::allowed) }
            ?: throw UcpException("${base.host} doesn't offer UCP over REST.")
        val capabilities = capabilityNames(ucp["capabilities"])
        val store = Store(
            address = if (base.scheme == "https" && base.port == 443) base.host else base.toString().trimEnd('/'),
            name = base.host.removePrefix("www."),
            endpoint = endpoint.toString().trimEnd('/'),
            capabilities = capabilities,
            version = version,
        )
        if (!store.canSearch && !store.canCheckout) throw UcpException("${base.host} offers neither product search nor checkout.")
        return store
    }

    suspend fun search(store: Store, query: String, maxPrice: Long?, limit: Int, country: String, language: String): List<Product> {
        val body = buildJsonObject {
            if (query.isNotBlank()) put("query", query)
            putJsonObject("context") {
                if (country.isNotBlank()) put("address_country", country)
                if (language.isNotBlank()) put("language", language)
            }
            if (maxPrice != null) putJsonObject("filters") { putJsonObject("price") { put("max", maxPrice) } }
            putJsonObject("pagination") { put("limit", limit) }
        }
        val response = post(endpoint(store, "catalog/search"), body, idempotencyKey = null)
        businessError(response)
        return (response["products"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::product) }
    }

    /** Opens a checkout session. The store prices it, and says where the user finishes it. */
    suspend fun createCheckout(store: Store, items: List<Pair<String, Int>>, idempotencyKey: String): Checkout {
        val body = buildJsonObject {
            putJsonArray("line_items") {
                items.forEach { (id, quantity) ->
                    add(buildJsonObject {
                        putJsonObject("item") { put("id", id) }
                        put("quantity", quantity)
                    })
                }
            }
        }
        val response = post(endpoint(store, "checkout-sessions"), body, idempotencyKey)
        businessError(response)
        return checkout(response)
    }

    private fun baseUrl(address: String): HttpUrl {
        val trimmed = address.trim().trimEnd('/')
        val url = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull()
            ?: throw UcpException("\"$address\" isn't a web address.")
        if (!allowed(url)) throw UcpException("Stores must use https.")
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }

    private fun allowed(url: HttpUrl) = url.isHttps || allowPlainHttp(url)

    private fun endpoint(store: Store, path: String): HttpUrl {
        val base = store.endpoint.toHttpUrlOrNull()?.takeIf(::allowed) ?: throw UcpException("${store.name}'s endpoint isn't usable.")
        return base.newBuilder().addPathSegments(path).build()
    }

    private suspend fun get(url: HttpUrl): JsonObject =
        send(Request.Builder().url(url).header("Accept", "application/json").get().build())

    private suspend fun post(url: HttpUrl, body: JsonObject, idempotencyKey: String?): JsonObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("UCP-Agent", "profile=\"$profileUrl\"")
            .header("Request-Id", UUID.randomUUID().toString())
            .header("Content-Digest", "sha-256=:$digest:")
            .apply { idempotencyKey?.let { header("Idempotency-Key", UUID.nameUUIDFromBytes(it.toByteArray()).toString()) } }
            .post(bytes.toRequestBody(JSON))
            .build()
        return send(request)
    }

    private suspend fun send(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                runCatching { response.use(::read) }.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private fun read(response: Response): JsonObject {
        val source = response.body.source()
        source.request(MAX_BYTES + 1)
        if (source.buffer.size > MAX_BYTES) throw UcpException("The store's reply is too large.")
        val text = source.buffer.readUtf8()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (!response.isSuccessful) {
            val message = json?.let(::messages)?.firstOrNull() ?: "HTTP ${response.code}"
            throw UcpException("The store answered: $message")
        }
        return json ?: throw UcpException("The store's reply isn't JSON.")
    }

    private fun businessError(response: JsonObject) {
        val status = (response["ucp"] as? JsonObject)?.string("status")
        if (status == "error") throw UcpException("The store answered: " + (messages(response).joinToString(" ").ifEmpty { "an error" }))
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_BYTES = 2L * 1024 * 1024

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        /** `services` may map names to one service or a list of them, or be a list itself. */
        fun restEndpoints(services: JsonElement?): List<String> {
            val all = when (services) {
                is JsonArray -> services.toList()
                is JsonObject -> services.values.flatMap { if (it is JsonArray) it.toList() else listOf(it) }
                else -> emptyList()
            }
            return all.mapNotNull { it as? JsonObject }
                .filter { it.string("transport").equals("rest", ignoreCase = true) }
                .mapNotNull { it.string("endpoint") }
        }

        /** `capabilities` maps names to versions, or lists objects that carry a name. */
        fun capabilityNames(capabilities: JsonElement?): Set<String> = when (capabilities) {
            is JsonObject -> capabilities.keys
            is JsonArray -> capabilities.mapNotNull { (it as? JsonObject)?.string("name") }.toSet()
            else -> emptySet()
        }

        fun money(element: JsonElement?): Money? {
            val obj = element as? JsonObject ?: return null
            val amount = (obj["amount"] as? JsonPrimitive)?.longOrNull ?: return null
            return Money(amount, obj.string("currency") ?: return null)
        }

        fun product(obj: JsonObject): Product? {
            val id = obj.string("id") ?: return null
            val range = obj["price_range"] as? JsonObject
            return Product(
                id = id,
                title = obj.string("title") ?: id,
                description = (obj["description"] as? JsonObject)?.string("plain"),
                url = obj.string("url"),
                minPrice = money(range?.get("min")),
                maxPrice = money(range?.get("max")),
                variants = (obj["variants"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val variant = element as? JsonObject ?: return@mapNotNull null
                    Variant(
                        id = variant.string("id") ?: return@mapNotNull null,
                        title = variant.string("title"),
                        price = money(variant["price"]),
                        available = ((variant["availability"] as? JsonObject)?.get("available") as? JsonPrimitive)?.booleanOrNull,
                    )
                },
            )
        }

        fun checkout(obj: JsonObject): Checkout {
            val totals = (obj["totals"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val currency = obj.string("currency")
            val total = totals.firstOrNull { it.string("type") == "total" }?.let { entry ->
                (entry["amount"] as? JsonPrimitive)?.longOrNull?.let { Money(it, entry.string("currency") ?: currency ?: return@let null) }
            }
            return Checkout(
                id = obj.string("id") ?: throw UcpException("The store's checkout has no id."),
                status = obj.string("status") ?: "unknown",
                lines = (obj["line_items"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val line = element as? JsonObject ?: return@mapNotNull null
                    val item = line["item"] as? JsonObject ?: return@mapNotNull null
                    CheckoutLine(
                        itemId = item.string("id") ?: return@mapNotNull null,
                        title = item.string("title"),
                        quantity = (line["quantity"] as? JsonPrimitive)?.intOrNull ?: 1,
                        price = (item["price"] as? JsonPrimitive)?.longOrNull?.let { amount -> currency?.let { Money(amount, it) } } ?: money(item["price"]),
                    )
                },
                total = total,
                continueUrl = obj.string("continue_url"),
                messages = messages(obj),
            )
        }

        fun messages(obj: JsonObject): List<String> = (obj["messages"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("content") }
    }
}

/** The phone's country and language for UCP's search context, such as "US" and "en". */
object ShoppingLocale {
    fun country(): String = Locale.getDefault().country
    fun language(): String = Locale.getDefault().language
}
