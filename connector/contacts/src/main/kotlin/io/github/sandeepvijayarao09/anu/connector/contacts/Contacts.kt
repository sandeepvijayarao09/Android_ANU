package io.github.sandeepvijayarao09.anu.connector.contacts

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

data class Contact(val name: String, val emails: List<String>, val phones: List<String>)

/** The address book as the tool sees it, so the tool can be tested without Android's provider. */
fun interface ContactSource {
    suspend fun find(name: String, limit: Int): List<Contact>

    /** Everyone's name, for finding one spelled a little differently (ADR-085). None by default. */
    suspend fun names(): List<String> = emptyList()
}

@Serializable
data class FindContactInput(@Description("A name or part of one, e.g. Maya") val name: String) {
    init {
        require(name.isNotBlank() && name.length <= 100) { "name must be 1-100 characters" }
    }
}

@Serializable
data class ContactOut(val name: String, val emails: List<String>, val phones: List<String>)

@Serializable
data class ContactsOutput(
    val matches: List<ContactOut>,
    /** Set when no one has the name asked for, and [matches] have names close to it. */
    val note: String? = null,
)

/**
 * Looks people up so the agent never guesses an address (§7.3 prompt rule). The user's own contacts
 * are not untrusted content.
 */
class FindContactTool(private val contacts: ContactSource) : Tool<FindContactInput, ContactsOutput> {
    override val name = "contacts_find"
    override val description = "Find people in the user's contacts by name, with their email addresses and phone numbers."
    override val input = FindContactInput.serializer()
    override val output = ContactsOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent: UntrustedContent? = null

    override fun risk(input: FindContactInput) = RiskTier.READ
    override fun describe(input: FindContactInput) = "Look up \"${input.name}\" in your contacts"

    override suspend fun invoke(input: FindContactInput, context: ToolContext): ContactsOutput {
        val name = input.name.trim()
        val found = contacts.find(name, MAX_MATCHES)
        if (found.isNotEmpty()) return ContactsOutput(found.map(::out))
        // A name heard in a voice note may be written a little differently: "Mya Chan" for Maya Chen. Offer the close
        // ones and say so; an invite still shows the exact address to approve (ADR-085).
        val close = closeNames(name, contacts.names(), MAX_MATCHES).flatMap { contacts.find(it, MAX_MATCHES) }.distinct().take(MAX_MATCHES)
        return if (close.isEmpty()) ContactsOutput(emptyList()) else ContactsOutput(close.map(::out), CLOSE)
    }

    private fun out(contact: Contact) = ContactOut(contact.name, contact.emails, contact.phones)

    private companion object {
        const val MAX_MATCHES = 5
        const val CLOSE = "No one has exactly that name. These names are close to it, as a name that was heard can be written " +
            "differently: use the name as it is here."
    }
}

/**
 * The names in [names] that are close to [query], closest first: each of its words is at most about a third of its
 * letters away from a word of the name ("Mya Chan" and Maya Chen). Words of one or two letters must match exactly.
 */
internal fun closeNames(query: String, names: List<String>, limit: Int): List<String> {
    val asked = words(query)
    if (asked.isEmpty()) return emptyList()
    return names.distinct().mapNotNull { name ->
        val parts = words(name)
        var total = 0
        for (word in asked) {
            val nearest = parts.minOfOrNull { distance(word, it) } ?: return@mapNotNull null
            if (nearest > if (word.length <= 2) 0 else maxOf(1, word.length / 3)) return@mapNotNull null
            total += nearest
        }
        name to total
    }.sortedWith(compareBy({ it.second }, { it.first })).take(limit).map { it.first }
}

private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}]+")).filter(String::isNotEmpty)

/** How many letters must be added, removed, changed or swapped with the next one to turn [a] into [b]. */
private fun distance(a: String, b: String): Int {
    val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
    }
    return d[a.length][b.length]
}

/** The phone's address book through `ContactsContract` (§9). Needs `READ_CONTACTS`. */
class ProviderContacts(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContactSource {

    override suspend fun find(name: String, limit: Int): List<Contact> = withContext(io) {
        val filter = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name))
        val people = mutableListOf<Pair<Long, String>>()
        resolver.query(
            filter,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { c -> while (c.moveToNext() && people.size < limit) people += c.getLong(0) to c.getString(1).orEmpty() }
        people.map { (id, display) ->
            Contact(
                name = display,
                emails = values(id, ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.CONTACT_ID),
                phones = values(id, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
            )
        }
    }

    override suspend fun names(): List<String> = withContext(io) {
        val all = mutableListOf<String>()
        resolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.takeIf(String::isNotBlank)?.let(all::add)
        }
        all
    }

    private fun values(contactId: Long, uri: Uri, column: String, idColumn: String): List<String> {
        val found = linkedSetOf<String>()
        resolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(contactId.toString()), null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.trim()?.takeIf(String::isNotEmpty)?.let(found::add)
        }
        return found.toList()
    }
}
