package io.github.sandeepvijayarao09.anu.core.security

import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class EgressTest {

    private val ledger = InMemoryLedger()
    private val gate = EgressGate(ledger, clock = { Instant.parse("2026-09-25T12:00:00Z") })

    private val request = BrainRequest(
        system = "You are Anu. The user is sam@home.org.",
        transcript = listOf(
            ChatMessage.User("Reply to maya@example.com and cc sam@home.org"),
            ChatMessage.Model("", listOf(ToolCall("email_get_thread", buildJsonObject { put("from", "maya@example.com") }))),
            ChatMessage.Tool(listOf(ToolResult("email_get_thread", buildJsonObject { put("body", "Call me at +1 617 555 0123") }))),
        ),
        dataClasses = setOf(DataClass.PERSONAL),
        purpose = "email",
    )

    @Test
    fun `every part of the request is redacted with stable placeholders`() = runTest {
        val prepared = gate.prepare(request, destination = "generativelanguage.googleapis.com")
        val sent = prepared.request.toString()
        listOf("maya@example.com", "sam@home.org", "617 555 0123").forEach { assertFalse("$it leaked", sent.contains(it)) }

        assertEquals("You are Anu. The user is [EMAIL_1].", prepared.request.system)
        assertEquals(ChatMessage.User("Reply to [EMAIL_2] and cc [EMAIL_1]"), prepared.request.transcript[0])
        val call = (prepared.request.transcript[1] as ChatMessage.Model).toolCalls.single()
        assertEquals(JsonPrimitive("[EMAIL_2]"), call.arguments["from"])
    }

    @Test
    fun `shared facts leave redacted like the rest, and the ledger counts them`() = runTest {
        val facts = listOf("manager: Maya Chen", "Maya Chen · email: maya@example.com")
        val prepared = gate.prepare(request.copy(sharedFacts = facts), destination = "generativelanguage.googleapis.com")
        assertEquals(listOf("manager: Maya Chen", "Maya Chen · email: [EMAIL_2]"), prepared.request.sharedFacts)
        val withFacts = ledger.entries.value.single()
        assertEquals("email · 2 facts from memory", withFacts.purpose)
        assertFalse(withFacts.toString().contains("Maya Chen"))

        val plainLedger = InMemoryLedger()
        EgressGate(plainLedger).prepare(request, destination = "generativelanguage.googleapis.com")
        val plain = plainLedger.entries.value.single()
        assertEquals(plain.characters + prepared.request.sharedFacts.sumOf { it.length }, withFacts.characters)
    }

    @Test
    fun `each egress is recorded without its content`() = runTest {
        gate.prepare(request, destination = "generativelanguage.googleapis.com")
        val entry = ledger.entries.value.single()
        assertEquals("generativelanguage.googleapis.com", entry.destination)
        assertEquals("email", entry.purpose)
        assertEquals(mapOf(PiiKind.EMAIL to 2, PiiKind.PHONE to 1), entry.redacted)
        assertEquals(setOf(DataClass.PERSONAL), entry.dataClasses)
        assertFalse(entry.toString().contains("maya@example.com"))
    }

    @Test
    fun `responses are restored locally, including across streamed chunks and tool arguments`() = runTest {
        val session = gate.prepare(request, destination = "cloud").redaction
        val restorer = session.streamRestorer()
        val shown = listOf("Sent to [EMA", "IL_2] and [EMAIL_1", "]. [not a placeholder]").joinToString("") { restorer.push(it) } +
            restorer.flush()
        assertEquals("Sent to maya@example.com and sam@home.org. [not a placeholder]", shown)
        assertEquals(
            buildJsonObject { put("to", "maya@example.com") },
            session.restore(buildJsonObject { put("to", "[EMAIL_2]") }),
        )
    }

    @Test
    fun `secrets are refused at the gate`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { gate.prepare(request.copy(dataClasses = setOf(DataClass.SECRET)), "cloud") }
        }
        assertEquals(emptyList<LedgerEntry>(), ledger.entries.value)
    }
}
