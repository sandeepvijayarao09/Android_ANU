package io.github.sandeepvijayarao09.anu.connector.contacts

import android.content.ContentProviderOperation
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The contacts connector against Android's real ContactsContract provider. */
@RunWith(AndroidJUnit4::class)
class ProviderContactsTest {

    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)

    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private var rawId = -1L

    @Before
    fun addContact() {
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Maya Testperson").build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, "maya@example.com").build(),
        )
        rawId = android.content.ContentUris.parseId(resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!)
    }

    @After
    fun removeContact() {
        resolver.delete(
            ContactsContract.RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawId.toString()),
        )
    }

    @Test
    fun findsPeopleByPartOfTheirName() = runBlocking {
        val match = ProviderContacts(resolver).find("Maya", limit = 5).single { it.name == "Maya Testperson" }
        assertEquals(listOf("maya@example.com"), match.emails)
    }
}
