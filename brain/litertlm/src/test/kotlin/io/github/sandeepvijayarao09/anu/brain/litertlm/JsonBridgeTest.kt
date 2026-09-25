package io.github.sandeepvijayarao09.anu.brain.litertlm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonBridgeTest {

    @Test
    fun `round-trips nested JSON through plain Kotlin values`() {
        val json = Json.parseToJsonElement(
            """{"city":"Paris","days":3,"metric":true,"temp":18.5,"tags":["a","b"],"extra":{"x":null}}""",
        )
        assertEquals(json, json.toPlain().toJsonElement())
    }

    @Test
    fun `converts runtime values the way LiteRT-LM returns them`() {
        val plain = mapOf("n" to 2.0, "list" to listOf(1, "two"), "none" to null)
        assertEquals(
            Json.parseToJsonElement("""{"n":2.0,"list":[1,"two"],"none":null}"""),
            plain.toJsonElement(),
        )
    }
}
