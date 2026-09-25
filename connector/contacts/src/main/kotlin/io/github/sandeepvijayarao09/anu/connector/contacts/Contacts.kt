package io.github.sandeepvijayarao09.anu.connector.contacts

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

data class Contact(val name: String, val emails: List<String>, val phones: List<String>)

/** The address book as the tool sees it, so the tool can be tested without Android's provider. */
fun interface ContactSource {
    suspend fun find(name: String, limit: Int): List<Contact>
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
data class ContactsOutput(val matches: List<ContactOut>)

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
    override val returnsUntrustedContent = false

    override fun risk(input: FindContactInput) = RiskTier.READ
    override fun describe(input: FindContactInput) = "Look up \"${input.name}\" in your contacts"

    override suspend fun invoke(input: FindContactInput, context: ToolContext) =
        ContactsOutput(contacts.find(input.name.trim(), MAX_MATCHES).map { ContactOut(it.name, it.emails, it.phones) })

    private companion object {
        const val MAX_MATCHES = 5
    }
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

    private fun values(contactId: Long, uri: Uri, column: String, idColumn: String): List<String> {
        val found = linkedSetOf<String>()
        resolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(contactId.toString()), null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.trim()?.takeIf(String::isNotEmpty)?.let(found::add)
        }
        return found.toList()
    }
}
