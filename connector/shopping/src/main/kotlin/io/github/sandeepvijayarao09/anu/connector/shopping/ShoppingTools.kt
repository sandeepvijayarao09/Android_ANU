package io.github.sandeepvijayarao09.anu.connector.shopping

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import java.time.Instant

/** Products seen in search results, so the approval sheet can name what is being bought and its price. */
class SeenProducts(private val capacity: Int = 200) {
    data class Item(val title: String, val price: Money?)

    private val items = object : LinkedHashMap<String, Item>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>?) = size > capacity
    }

    fun remember(store: Store, product: Product) = synchronized(items) {
        items["${store.address}|${product.id}"] = Item(product.title, product.minPrice)
        product.variants.forEach { variant ->
            val title = variant.title?.let { "${product.title}, $it" } ?: product.title
            items["${store.address}|${variant.id}"] = Item(title, variant.price ?: product.minPrice)
        }
    }

    fun find(store: Store, itemId: String): Item? = synchronized(items) { items["${store.address}|$itemId"] }
}

/** Finds the store the model named, by its name or address. */
internal fun List<Store>.named(name: String): Store {
    val wanted = name.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    return firstOrNull { it.name.lowercase() == wanted || it.address.lowercase().removePrefix("www.") == wanted }
        ?: singleOrNull { wanted.isNotEmpty() && (wanted in it.name.lowercase() || it.name.lowercase() in wanted) }
        ?: throw IllegalArgumentException(
            if (isEmpty()) "The user hasn't added any stores. They can add one in Settings, Shopping." else "No store called \"$name\". The user's stores: ${joinToString { it.name }}",
        )
}

@Serializable
data class ShopSearchInput(
    @Description("What to look for, such as \"trail running shoes\"")
    val query: String,
    @Description("Only this store: its name as shop_search returns it. Leave out to search every store")
    val store: String? = null,
    @Description("The most to pay for one, in the store's currency, such as 120 or 49.99")
    val max_price: Double? = null,
)

@Serializable
data class FoundVariant(val variant_id: String, val title: String? = null, val price: String? = null, val available: Boolean? = null)

@Serializable
data class FoundProduct(
    val store: String,
    val product_id: String,
    val title: String,
    val price: String? = null,
    val description: String? = null,
    val variants: List<FoundVariant> = emptyList(),
)

@Serializable
data class ShopSearchOutput(val results: List<FoundProduct>, val notes: List<String> = emptyList())

/**
 * `shop_search` (READ, untrusted): searches the user's stores through UCP catalog search (ADR-047).
 * The query goes to each store, so every search writes a ledger entry (I3), and once the run is tainted
 * it needs approval ([sendsTo]).
 */
class ShopSearchTool(
    private val stores: () -> List<Store>,
    private val client: UcpClient,
    private val seen: SeenProducts,
    private val ledger: PrivacyLedger?,
    private val locale: () -> Pair<String, String> = { ShoppingLocale.country() to ShoppingLocale.language() },
    private val clock: () -> Instant = Instant::now,
) : Tool<ShopSearchInput, ShopSearchOutput> {
    override val name = "shop_search"
    override val description = "Search the user's stores for products and prices. Returns the ids shop_checkout needs."
    override val input: KSerializer<ShopSearchInput> = ShopSearchInput.serializer()
    override val output: KSerializer<ShopSearchOutput> = ShopSearchOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = true

    private fun targets(input: ShopSearchInput): List<Store> {
        val all = stores()
        return input.store?.takeIf(String::isNotBlank)?.let { listOf(all.named(it)) } ?: all.filter(Store::canSearch)
    }

    override fun risk(input: ShopSearchInput): RiskTier {
        require(input.query.isNotBlank()) { "Say what to look for" }
        require(input.max_price == null || input.max_price > 0) { "max_price must be above 0" }
        targets(input)
        return RiskTier.READ
    }

    override fun describe(input: ShopSearchInput): String {
        val where = targets(input).joinToString { it.name }.ifEmpty { "your stores" }
        return "Search $where for \"${input.query.trim()}\"" + (input.max_price?.let { " up to ${BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()}" } ?: "")
    }

    override fun sendsTo(input: ShopSearchInput): String? = targets(input).map { it.endpointHost }.distinct().joinToString().ifEmpty { null }

    override suspend fun invoke(input: ShopSearchInput, context: ToolContext): ShopSearchOutput {
        val targets = targets(input)
        if (targets.isEmpty()) return ShopSearchOutput(emptyList(), listOf("The user hasn't added any stores. They can add one in Settings, Shopping."))
        val (country, language) = locale()
        val results = mutableListOf<FoundProduct>()
        val notes = mutableListOf<String>()
        for (store in targets) {
            if (!store.canSearch) {
                notes += "${store.name} doesn't offer search."
                continue
            }
            ledger?.record(LedgerEntry(clock(), store.endpointHost, "Store search: ${store.name}", dataClasses, emptyMap(), input.query.length))
            try {
                // UCP filters in minor units; most currencies have two decimals. Results are checked again below.
                val maxMinor = input.max_price?.let { BigDecimal.valueOf(it).movePointRight(2).toLong() }
                client.search(store, input.query.trim(), maxMinor, PER_STORE, country, language).forEach { product ->
                    if (input.max_price != null && product.minPrice != null && product.minPrice.toDecimal() > BigDecimal.valueOf(input.max_price)) return@forEach
                    seen.remember(store, product)
                    results += FoundProduct(
                        store = store.name,
                        product_id = product.id,
                        title = product.title.take(TITLE),
                        price = priceText(product),
                        description = product.description?.take(DESCRIPTION),
                        variants = product.variants.take(VARIANTS).map { FoundVariant(it.id, it.title?.take(TITLE), it.price?.format(), it.available) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "${store.name}: ${e.message ?: "couldn't be searched"}"
            }
        }
        if (results.isEmpty() && notes.isEmpty()) notes += "Nothing matched."
        return ShopSearchOutput(results.take(MAX_RESULTS), notes)
    }

    private fun priceText(product: Product): String? {
        val min = product.minPrice ?: return null
        val max = product.maxPrice
        return if (max == null || max.amount == min.amount) min.format() else "${min.format()} to ${max.format()}"
    }

    private companion object {
        const val PER_STORE = 5
        const val MAX_RESULTS = 8
        const val VARIANTS = 4
        const val TITLE = 80
        const val DESCRIPTION = 160
    }
}

@Serializable
data class CheckoutItemInput(
    @Description("A variant_id from shop_search, or the product_id when it has no variants")
    val item_id: String,
    val quantity: Int = 1,
)

@Serializable
data class ShopCheckoutInput(
    @Description("The store, as shop_search named it")
    val store: String,
    val items: List<CheckoutItemInput>,
)

@Serializable
data class ShopCheckoutOutput(
    val store: String,
    val status: String,
    val total: String? = null,
    val items: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    /** A link the chat shows as a button. Anu never opens it by itself. */
    val open_url: String? = null,
    val open_label: String? = null,
    val next_step: String,
)

/**
 * `shop_checkout` (WRITE_EXTERNAL): opens a checkout session at one of the user's stores (ADR-047).
 * - The user approves the exact items, the store and the estimated total first (I2).
 * - The store prices the order and says where to finish it. The user reviews, pays and places the order
 *   on the store's own site: Anu never completes a checkout and never touches payment details.
 */
class ShopCheckoutTool(
    private val stores: () -> List<Store>,
    private val client: UcpClient,
    private val seen: SeenProducts,
    private val ledger: PrivacyLedger?,
    /** Whether a link may be offered: https, or a local test store in debug builds. */
    private val linkAllowed: (String) -> Boolean = { it.toHttpUrlOrNull()?.isHttps == true },
    private val clock: () -> Instant = Instant::now,
) : Tool<ShopCheckoutInput, ShopCheckoutOutput> {
    override val name = "shop_checkout"
    override val description = "Start a checkout at one of the user's stores. The user approves it, then reviews and pays on the store's site."
    override val input: KSerializer<ShopCheckoutInput> = ShopCheckoutInput.serializer()
    override val output: KSerializer<ShopCheckoutOutput> = ShopCheckoutOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = true

    private fun store(input: ShopCheckoutInput): Store = stores().named(input.store).also {
        require(it.canCheckout) { "${it.name} doesn't offer checkout through Anu. The user can buy on its site." }
    }

    override fun risk(input: ShopCheckoutInput): RiskTier {
        store(input)
        require(input.items.isNotEmpty()) { "Say what to buy" }
        require(input.items.size <= MAX_ITEMS) { "At most $MAX_ITEMS different items at once" }
        input.items.forEach { item ->
            require(item.item_id.isNotBlank()) { "Every item needs an item_id from shop_search" }
            require(item.quantity in 1..MAX_QUANTITY) { "Quantities go from 1 to $MAX_QUANTITY" }
        }
        return RiskTier.WRITE_EXTERNAL
    }

    override fun describe(input: ShopCheckoutInput): String {
        val store = store(input)
        val lines = input.items.map { item ->
            val known = seen.find(store, item.item_id)
            "${item.quantity} × " + (known?.let { it.title + (it.price?.let { price -> " (${price.format()} each)" } ?: "") } ?: "item ${item.item_id}")
        }
        val prices = input.items.map { item -> seen.find(store, item.item_id)?.price?.let { it to item.quantity } }
        val estimate = if (prices.all { it != null } && prices.map { it!!.first.currency }.distinct().size == 1) {
            val (first, _) = prices.first()!!
            Money(prices.sumOf { it!!.first.amount * it.second }, first.currency).format()
        } else {
            null
        }
        return "Start a checkout at ${store.name}: ${lines.joinToString("; ")}." +
            (estimate?.let { " About $it before tax and shipping." } ?: "") +
            " You'll review and pay on the store's site."
    }

    override fun sendsTo(input: ShopCheckoutInput): String = store(input).endpointHost

    override suspend fun invoke(input: ShopCheckoutInput, context: ToolContext): ShopCheckoutOutput {
        val store = store(input)
        ledger?.record(LedgerEntry(clock(), store.endpointHost, "Checkout at ${store.name}", dataClasses, emptyMap(), input.items.sumOf { it.item_id.length + 4 }))
        val checkout = client.createCheckout(store, input.items.map { it.item_id to it.quantity }, context.idempotencyKey)
        val link = checkout.continueUrl?.takeIf(linkAllowed)
        val host = link?.toHttpUrlOrNull()?.host
        return ShopCheckoutOutput(
            store = store.name,
            status = checkout.status,
            total = checkout.total?.format(),
            items = checkout.lines.map { "${it.quantity} × ${it.title ?: it.itemId}" + (it.price?.let { price -> " (${price.format()})" } ?: "") },
            messages = checkout.messages.take(5),
            open_url = link,
            open_label = host?.let { "Finish at $it" },
            next_step = if (link != null) {
                "Nothing is bought yet. Tell the user the total, and that they review and pay on ${store.name}'s site with the button below."
            } else {
                "Nothing is bought yet. The store gave no link, so the user finishes on ${store.name}'s site."
            },
        )
    }

    private companion object {
        const val MAX_ITEMS = 10
        const val MAX_QUANTITY = 20
    }
}

private fun Money.toDecimal(): BigDecimal {
    val digits = runCatching { java.util.Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    return BigDecimal.valueOf(amount, digits)
}
