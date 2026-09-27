package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.EditableField.Kind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApprovalEditTest {

    private val email = listOf(
        EditableField("to", "To", Kind.LIST),
        EditableField("cc", "Cc", Kind.LIST),
        EditableField("subject", "Subject", Kind.LINE),
        EditableField("body", "Message", Kind.TEXT),
    )

    private val payload = buildJsonObject {
        putJsonArray("to") { add("maya@example.com") }
        put("subject", "Lunch")
        put("body", "See you at 1?")
        putJsonArray("cc") {}
        put("replyToThreadId", JsonNull)
    }

    @Test
    fun `the form shows lists with commas, and nothing for an empty list`() {
        val draft = draftOf(payload, email)
        assertEquals("maya@example.com", draft["to"])
        assertEquals("", draft["cc"])
        assertEquals("See you at 1?", draft["body"])
    }

    @Test
    fun `saving the form unchanged gives back the same payload`() {
        assertEquals(payload, revisedPayload(payload, email, draftOf(payload, email)))
    }

    @Test
    fun `lists split at commas, text is trimmed, and other fields stay exactly as they were`() {
        val draft = draftOf(payload, email) + ("to" to "maya@example.com, raj@example.com ;") + ("subject" to "  Lunch at 1  ")
        val revised = revisedPayload(payload, email, draft)
        assertEquals(JsonArray(listOf(JsonPrimitive("maya@example.com"), JsonPrimitive("raj@example.com"))), revised["to"])
        assertEquals("Lunch at 1", revised["subject"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, revised["replyToThreadId"])
    }

    @Test
    fun `an optional field emptied is removed, and one that was already empty stays`() {
        val place = listOf(EditableField("location", "Place", Kind.LINE, optional = true))
        val withPlace = buildJsonObject {
            put("title", "Lunch")
            put("location", "Cafe")
        }
        assertFalse("location" in revisedPayload(withPlace, place, mapOf("location" to " ")))
        val noPlace = buildJsonObject {
            put("title", "Lunch")
            put("location", JsonNull)
        }
        assertEquals(noPlace, revisedPayload(noPlace, place, mapOf("location" to "")))
    }
}
