package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonSchemasTest {

    @Serializable
    enum class Urgency { LOW, HIGH }

    @Serializable
    data class Attendee(val email: String)

    @Serializable
    @Description("Create a calendar event")
    data class CreateEvent(
        @Description("Short event title") val title: String,
        val durationMinutes: Int,
        val allDay: Boolean,
        val weight: Double,
        val urgency: Urgency,
        val attendees: List<Attendee>,
        val labels: Map<String, String> = emptyMap(),
        val location: String? = null,
    )

    @Test
    fun `generates schema with types, required fields and descriptions`() {
        val expected = Json.parseToJsonElement(
            """
            {
              "type": "object",
              "properties": {
                "title": {"type": "string", "description": "Short event title"},
                "durationMinutes": {"type": "integer"},
                "allDay": {"type": "boolean"},
                "weight": {"type": "number"},
                "urgency": {"type": "string", "enum": ["LOW", "HIGH"]},
                "attendees": {
                  "type": "array",
                  "items": {"type": "object", "properties": {"email": {"type": "string"}}, "required": ["email"]}
                },
                "labels": {"type": "object", "additionalProperties": {"type": "string"}},
                "location": {"type": "string"}
              },
              "required": ["title", "durationMinutes", "allDay", "weight", "urgency", "attendees"],
              "description": "Create a calendar event"
            }
            """,
        )
        assertEquals(expected, JsonSchemas.of(CreateEvent.serializer()))
    }

    @Serializable
    sealed interface Shape

    @Serializable
    data class Holder(val shape: Shape)

    @Test
    fun `rejects polymorphic inputs when the spec is built`() {
        assertThrows(IllegalArgumentException::class.java) { JsonSchemas.of(Holder.serializer()) }
    }

    @Test
    fun `tool spec requires snake_case names`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolSpec(name = "email.getThread", description = "x", parameters = JsonObject(emptyMap()))
        }
        ToolSpec.of("email_get_thread", "Read one email thread", Attendee.serializer())
    }
}
