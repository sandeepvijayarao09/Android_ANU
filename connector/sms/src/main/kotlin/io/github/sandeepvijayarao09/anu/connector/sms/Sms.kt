package io.github.sandeepvijayarao09.anu.connector.sms

import android.content.ContentResolver
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.security.OneTimeCodes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SmsMessage(val address: String, val body: String, val at: Instant, val incoming: Boolean)

/** The SMS inbox as the tools see it, so the tools can be tested without Android's provider. */
interface SmsSource {
    /** Newest first. [text] and [from] are optional substring filters. */
    suspend fun search(text: String?, from: String?, max: Int): List<SmsMessage>
}

/**
 * The phone's SMS history from the `Telephony` provider (ARCHITECTURE §9). This is the `personal`
 * flavor only, and read-only: it needs `READ_SMS`, never `SEND_SMS` (ADR-032).
 */
class ProviderSms(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val country: () -> String = { Locale.getDefault().country },
) : SmsSource {

    override suspend fun search(text: String?, from: String?, max: Int): List<SmsMessage> = withContext(io) {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        text?.takeIf(String::isNotBlank)?.let { clauses += "${Telephony.Sms.BODY} LIKE ?"; args += "%$it%" }
        from?.takeIf(String::isNotBlank)?.let { clauses += "${Telephony.Sms.ADDRESS} LIKE ?"; args += "%${it.filter { c -> c.isLetterOrDigit() || c == '+' }}%" }
        clauses += "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"
        val messages = mutableListOf<SmsMessage>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && messages.size < max) {
                messages += SmsMessage(
                    address = normalized(c.getString(0).orEmpty()),
                    body = c.getString(1).orEmpty(),
                    at = Instant.ofEpochMilli(c.getLong(2)),
                    incoming = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                )
            }
        }
        messages
    }

    /**
     * One form per number. The provider stores "+15551234567" for received texts and "5551234567" for
     * sent ones, and the emulator showed the model taking them for two people.
     */
    private fun normalized(address: String): String =
        PhoneNumberUtils.formatNumberToE164(address, country())?.takeIf { it.isNotEmpty() } ?: address
}

private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

@Serializable
data class SmsSearchInput(
    @Description("Words to find in the text, optional") val text: String? = null,
    @Description("Part of the sender's number, optional") val from: String? = null,
    @Description("How many, 1-30") val max: Int = 10,
) {
    init {
        require(max in 1..30) { "max must be 1-30" }
        require((text?.length ?: 0) <= 200 && (from?.length ?: 0) <= 40) { "filters are too long" }
    }
}

@Serializable
data class SmsOut(val from: String, val text: String, val at: String, val direction: String)

@Serializable
data class SmsSearchOutput(val messages: List<SmsOut>)

/**
 * Searches SMS history. Others wrote the messages, so they are untrusted (P4). Verification codes are
 * masked before the model sees them (ADR-032, I1).
 */
class SmsSearchTool(private val sms: SmsSource, private val zone: () -> ZoneId) : Tool<SmsSearchInput, SmsSearchOutput> {
    override val name = "sms_search"
    override val description = "Search the phone's SMS text messages by words or sender, newest first."
    override val input = SmsSearchInput.serializer()
    override val output = SmsSearchOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = true

    override fun risk(input: SmsSearchInput) = RiskTier.READ
    override fun describe(input: SmsSearchInput) = buildString {
        append("Search your texts")
        input.text?.let { append(" for \"").append(it).append('"') }
        input.from?.let { append(" from ").append(it) }
    }

    override suspend fun invoke(input: SmsSearchInput, context: ToolContext): SmsSearchOutput {
        val zone = zone()
        return SmsSearchOutput(
            sms.search(input.text, input.from, input.max).map {
                SmsOut(it.address, OneTimeCodes.mask(it.body), LOCAL.format(it.at.atZone(zone)), if (it.incoming) "received" else "sent")
            },
        )
    }
}
