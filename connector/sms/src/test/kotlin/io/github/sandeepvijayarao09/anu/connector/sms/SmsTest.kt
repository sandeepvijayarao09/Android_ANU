package io.github.sandeepvijayarao09.anu.connector.sms

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SmsTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val source = object : SmsSource {
        var lastQuery: Triple<String?, String?, Int>? = null
        override suspend fun search(text: String?, from: String?, max: Int): List<SmsMessage> {
            lastQuery = Triple(text, from, max)
            return listOf(
                SmsMessage("+15551234567", "Lunch at 1?", Instant.parse("2026-09-25T18:01:00Z"), incoming = true),
                SmsMessage("32665", "Your sign-in code is 482913", Instant.parse("2026-09-25T18:00:00Z"), incoming = true),
            )
        }
    }

    @Test
    fun `sms search is read-only, masks codes, and passes its filters`() = runTest {
        val tool = SmsSearchTool(source) { zone }
        val input = SmsSearchInput(text = "lunch", from = "555", max = 5)
        assertEquals(RiskTier.READ, tool.risk(input))
        assertEquals("Search your texts for \"lunch\" from 555", tool.describe(input))
        val out = tool.invoke(input, ToolContext("r", "k"))
        assertEquals(Triple("lunch", "555", 5), source.lastQuery)
        assertEquals(listOf("Lunch at 1?", "Your sign-in code is ••••••"), out.messages.map { it.text })
        assertEquals("2026-09-25T11:01", out.messages.first().at)
    }

    @Test
    fun `filters are bounded`() {
        assertThrows(IllegalArgumentException::class.java) { SmsSearchInput(max = 99) }
        assertThrows(IllegalArgumentException::class.java) { SmsSearchInput(text = "x".repeat(201)) }
    }
}
