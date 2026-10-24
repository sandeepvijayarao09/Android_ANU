package io.github.sandeepvijayarao09.anu.testshop

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Made-up products. Nothing here is a real store or a real price. */
private data class Product(val name: String, val price: Int, val kind: String)

private val PRODUCTS = listOf(
    Product("Trail Runner 2", 120, "Running shoe"),
    Product("Road Glide", 95, "Running shoe"),
    Product("Court Classic", 80, "Tennis shoe"),
    Product("Cloud Walk", 110, "Walking shoe"),
    Product("Oat Milk", 4, "Groceries"),
    Product("Sourdough Loaf", 6, "Groceries"),
)

private const val SIZES = 4
private val SIZE_CHOICES = (8 until 8 + SIZES).toList()

private data class CartItem(val product: Product, val size: Int?)

/**
 * Test Shop: a made-up shop for app control's checks on anu_api36 (ADR-098). Search, a product with sizes, a cart with
 * Remove, and a checkout whose "Place order" orders nothing. Every cart change is logged under the tag TestShop, so a
 * check can read what Anu did: `adb logcat -d -s TestShop`.
 */
class ShopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Shop() } } }
    }
}

private sealed interface Page {
    data object Home : Page
    data class Details(val product: Product) : Page
    data object Cart : Page
    data object Checkout : Page
}

@Composable
private fun Shop() {
    var page by remember { mutableStateOf<Page>(Page.Home) }
    val cart = remember { mutableStateListOf<CartItem>() }
    BackHandler(page != Page.Home) {
        page = when (page) {
            Page.Checkout -> Page.Cart
            else -> Page.Home
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Test Shop", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { page = Page.Cart }) { Text("Cart (${cart.size})") }
        }
        Spacer(Modifier.padding(4.dp))
        when (val current = page) {
            Page.Home -> Home(onOpen = { page = Page.Details(it) })
            is Page.Details -> Details(current.product, onAdd = { size ->
                cart += CartItem(current.product, size)
                Log.i("TestShop", "cart: added ${current.product.name}${size?.let { ", size $it" }.orEmpty()}; ${cart.size} in cart")
            }, onBuy = { page = Page.Checkout }, onBack = { page = Page.Home })
            Page.Cart -> Cart(cart, onCheckout = { page = Page.Checkout }, onBack = { page = Page.Home })
            Page.Checkout -> Checkout(cart)
        }
    }
}

@Composable
private fun Home(onOpen: (Product) -> Unit) {
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it; applied = it },
        label = { Text("Search") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { applied = query }),
        modifier = Modifier.fillMaxWidth(),
    )
    val shown = PRODUCTS.filter { applied.isBlank() || it.name.contains(applied.trim(), ignoreCase = true) || it.kind.contains(applied.trim(), ignoreCase = true) }
    LazyColumn(Modifier.padding(top = 8.dp)) {
        items(shown) { product ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(product) }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(product.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(product.kind, fontSize = 13.sp)
                }
                Text("$${product.price}", fontSize = 17.sp)
            }
            HorizontalDivider()
        }
        if (shown.isEmpty()) item { Text("Nothing matches “$applied”.", Modifier.padding(vertical = 14.dp)) }
    }
}

@Composable
private fun Details(product: Product, onAdd: (Int?) -> Unit, onBuy: () -> Unit, onBack: () -> Unit) {
    var size by remember(product) { mutableStateOf<Int?>(null) }
    var said by remember(product) { mutableStateOf<String?>(null) }
    val sized = product.kind.endsWith("shoe")
    TextButton(onClick = onBack) { Text("‹ Back") }
    Text(product.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("$${product.price} · ${product.kind}", fontSize = 15.sp)
    if (sized) {
        Text("Size", Modifier.padding(top = 16.dp), fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            SIZE_CHOICES.forEach { choice ->
                val chosen = size == choice
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.toggleable(value = chosen, role = Role.Checkbox) { size = if (it) choice else null },
                ) {
                    Text(
                        "Size $choice",
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
    Button(
        onClick = {
            if (sized && size == null) {
                said = "Choose a size first."
            } else {
                onAdd(size)
                said = "Added to cart: ${product.name}${size?.let { ", size $it" }.orEmpty()}."
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) { Text("Add to cart") }
    OutlinedButton(onClick = onBuy, modifier = Modifier.fillMaxWidth()) { Text("Buy now") }
    said?.let { Text(it, Modifier.padding(top = 12.dp)) }
}

@Composable
private fun Cart(cart: MutableList<CartItem>, onCheckout: () -> Unit, onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text("‹ Back") }
    Text("Your cart", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    if (cart.isEmpty()) Text("Your cart is empty.", Modifier.padding(vertical = 12.dp))
    cart.forEachIndexed { index, item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${item.product.name}${item.size?.let { ", size $it" }.orEmpty()} · $${item.product.price}", Modifier.weight(1f))
            TextButton(onClick = {
                cart.removeAt(index)
                Log.i("TestShop", "cart: removed ${item.product.name}; ${cart.size} in cart")
            }) { Text("Remove") }
        }
    }
    if (cart.isNotEmpty()) {
        Text("Total: $${cart.sumOf { it.product.price }}", Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.SemiBold)
        Button(onClick = onCheckout, modifier = Modifier.fillMaxWidth()) { Text("Proceed to checkout") }
    }
}

@Composable
private fun Checkout(cart: List<CartItem>) {
    var placed by remember { mutableStateOf(false) }
    Text("Checkout", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("${cart.size} items · $${cart.sumOf { it.product.price }}", Modifier.padding(vertical = 12.dp))
    Button(onClick = {
        placed = true
        Log.i("TestShop", "order: placed (made up, nothing ordered)")
    }, modifier = Modifier.fillMaxWidth()) { Text("Place order") }
    if (placed) Text("Order placed. (This is a test shop: nothing was ordered.)", Modifier.padding(top = 12.dp))
}
