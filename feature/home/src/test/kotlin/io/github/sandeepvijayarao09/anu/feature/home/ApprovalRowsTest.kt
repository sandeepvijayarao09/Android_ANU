package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.PayloadRow
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalRowsTest {

    // The memory_remember payload the emulator's sheet listed as "About: user", "What: buys_at" and "Sensitive: false".
    private val payload = buildJsonObject {
        put("about", "user")
        put("what", "buys_at")
        put("value", "buy oat milk every week")
        put("section", "routines")
        put("sensitive", false)
    }

    private fun request(rows: List<PayloadRow>?) = ApprovalRequest(
        "key", "memory_remember", "Remember that you buy oat milk every week", payload, RiskTier.WRITE_PRIVATE,
        "This request read content written by others", rows = rows,
    )

    @Test
    fun `a tool's own rows are listed instead of its fields`() {
        val rows = listOf(PayloadRow("Fact", "You buy oat milk every week"), PayloadRow("Kept in", "Routines"))
        assertEquals(listOf("Fact" to "You buy oat milk every week", "Kept in" to "Routines"), sheetRows(request(rows)))
    }

    @Test
    fun `without them, every field of the payload is listed`() {
        assertEquals(payloadRows(payload), sheetRows(request(rows = null)))
    }
}
