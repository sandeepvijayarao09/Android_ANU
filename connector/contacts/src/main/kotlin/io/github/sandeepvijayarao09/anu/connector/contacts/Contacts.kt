package io.github.sandeepvijayarao09.anu.connector.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import io.github.sandeepvijayarao09.anu.core.agent.EditableField
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

/** Saves people in the address book, so the tool can be tested without Android's provider (ADR-093). */
fun interface ContactWriter {
    /**
     * Adds [phone] and [email], when given, to the contact named exactly [name], or creates that contact. Says which,
     * and the contact's name as the address book has it.
     */
    suspend fun save(name: String, phone: String?, email: String?): Saved

    data class Saved(val name: String, val created: Boolean)
}

@Serializable
data class AddContactInput(
    @Description("The person's full name, e.g. Maya Chen") val name: String,
    @Description("Their phone number, if the user gave one") val phone: String? = null,
    @Description("Their email address, if the user gave one") val email: String? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= 100) { "name must be 1-100 characters" }
        require(phone == null || PHONE_NUMBER.matches(phone.trim())) { "phone must be a phone number, such as +1 555 0142" }
        require(email == null || EMAIL_ADDRESS.matches(email.trim())) { "email must be an email address, such as maya@example.com" }
    }
}

private val PHONE_NUMBER = Regex("\\+?[\\d ().-]{3,20}")
private val EMAIL_ADDRESS = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

@Serializable
data class ContactSaved(val said: String)

/**
 * Saves a person, or a number or address for someone already saved under that exact name (ADR-093). The address
 * book is the user's own, and a contact can be deleted, so it's `WRITE_PRIVATE` (§7.1): it runs at once unless the
 * turn is tainted, as when a number came from someone else's message.
 */
class AddContactTool(private val contacts: ContactWriter) : Tool<AddContactInput, ContactSaved> {
    override val name = "contacts_add"
    override val description = "Save a person in the user's contacts with their phone number or email, or add one to " +
        "someone saved under that exact name"
    override val input = AddContactInput.serializer()
    override val output = ContactSaved.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent: UntrustedContent? = null
    // The name picks which contact is changed, so it stays out (§7.1).
    override val editable = listOf(
        EditableField("phone", "Phone", EditableField.Kind.LINE, optional = true),
        EditableField("email", "Email", EditableField.Kind.LINE, optional = true),
    )

    override fun risk(input: AddContactInput) = RiskTier.WRITE_PRIVATE

    override fun describe(input: AddContactInput): String {
        val details = listOfNotNull(input.phone?.trim()?.let { "phone $it" }, input.email?.trim()?.let { "email $it" })
        require(details.isNotEmpty()) { "give a phone number or an email address to save" }
        return "Save ${input.name.trim()} in your contacts, with ${details.joinToString(" and ")}"
    }

    override suspend fun invoke(input: AddContactInput, context: ToolContext): ContactSaved {
        val saved = contacts.save(input.name.trim(), input.phone?.trim(), input.email?.trim())
        return ContactSaved(if (saved.created) "Saved ${saved.name} in your contacts." else "Added that to ${saved.name} in your contacts.")
    }
}

/** The phone's address book through `ContactsContract` (§9). Needs `READ_CONTACTS`, and `WRITE_CONTACTS` to save. */
class ProviderContacts(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContactSource, ContactWriter {

    override suspend fun save(name: String, phone: String?, email: String?): ContactWriter.Saved = withContext(io) {
        val existing = rawContactOf(name)
        val ops = ArrayList<ContentProviderOperation>()
        // A new contact goes on the phone itself, with no account, so nothing is synced anywhere by Anu.
        if (existing == null) {
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
            ops += data(null).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build()
        }
        phone?.let {
            ops += data(existing).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, it)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        }
        email?.let {
            ops += data(existing).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, it)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                .build()
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        ContactWriter.Saved(existing?.second ?: name, created = existing == null)
    }

    /** A data row for the raw contact [existing], or for the one inserted first in the same batch. */
    private fun data(existing: Pair<Long, String>?): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).let {
            if (existing == null) it.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            else it.withValue(ContactsContract.Data.RAW_CONTACT_ID, existing.first)
        }

    /** The first raw contact of the contact named exactly [name], ignoring case, with the name as saved. */
    private fun rawContactOf(name: String): Pair<Long, String>? {
        val filter = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name))
        val contact = resolver.query(filter, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1).orEmpty() else null }.firstOrNull { it.second.equals(name, ignoreCase = true) }
        } ?: return null
        return resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contact.first.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) to contact.second else null }
    }

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
