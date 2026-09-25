package io.github.sandeepvijayarao09.anu.connector.gmail

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

internal val EMAIL_ADDRESS = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

data class OutgoingEmail(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val inReplyTo: String? = null,
    val references: String? = null,
)

/**
 * Builds RFC 5322 messages from validated fields. The model never supplies raw MIME. Header values with
 * CR or LF are refused, so injected text can't add headers or recipients (§9).
 */
object Mime {

    fun build(email: OutgoingEmail): String {
        require(email.to.isNotEmpty()) { "an email needs at least one recipient" }
        (email.to + email.cc).forEach { require(EMAIL_ADDRESS.matches(it)) { "\"$it\" is not an email address" } }
        val headers = buildList {
            add("MIME-Version" to "1.0")
            add("To" to email.to.joinToString(", "))
            if (email.cc.isNotEmpty()) add("Cc" to email.cc.joinToString(", "))
            add("Subject" to encodeWord(email.subject))
            email.inReplyTo?.let { add("In-Reply-To" to it) }
            email.references?.let { add("References" to it) }
            add("Content-Type" to "text/plain; charset=\"UTF-8\"")
            add("Content-Transfer-Encoding" to "base64")
        }
        return buildString {
            headers.forEach { (name, value) ->
                require('\r' !in value && '\n' !in value) { "a line break is not allowed in the $name header" }
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
            Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(email.body.toByteArray()).let { append(it).append("\r\n") }
        }
    }

    /** Gmail's `raw` field: the whole message, base64url-encoded. */
    fun base64Url(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun encodeWord(value: String): String {
        require('\r' !in value && '\n' !in value) { "a line break is not allowed in the Subject" }
        return if (value.all { it.code in 0x20..0x7e }) value else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray()) + "?="
    }
}

/** Reads Gmail's message JSON: headers, and the best text body from nested MIME parts. */
internal object GmailParsing {
    const val MAX_TEXT = 4_000

    fun headers(message: JsonObject): Map<String, String> =
        message["payload"]?.jsonObject?.get("headers")?.jsonArray.orEmpty().associate {
            val header = it.jsonObject
            header.getValue("name").jsonPrimitive.content.lowercase() to header.getValue("value").jsonPrimitive.content
        }

    fun message(json: JsonObject): ThreadMessage {
        val headers = headers(json)
        val payload = json["payload"]?.jsonObject
        val text = payload?.let { part(it, "text/plain") } ?: payload?.let { part(it, "text/html") }?.let(::htmlToText) ?: ""
        return ThreadMessage(
            id = json.getValue("id").jsonPrimitive.content,
            from = headers["from"].orEmpty(),
            to = headers["to"].orEmpty(),
            cc = headers["cc"].orEmpty(),
            subject = headers["subject"].orEmpty(),
            date = headers["date"].orEmpty(),
            messageId = headers["message-id"],
            text = text.trim().let { if (it.length > MAX_TEXT) it.take(MAX_TEXT) + " […]" else it },
        )
    }

    /** Depth-first search for the first part of [mimeType] with data. */
    private fun part(node: JsonObject, mimeType: String): String? {
        val type = node["mimeType"]?.jsonPrimitive?.content
        val data = node["body"]?.jsonObject?.get("data")?.jsonPrimitive?.content
        if (type == mimeType && !data.isNullOrEmpty()) return String(Base64.getUrlDecoder().decode(data.padEnd((data.length + 3) / 4 * 4, '=')))
        return node["parts"]?.jsonArray.orEmpty().firstNotNullOfOrNull { part(it.jsonObject, mimeType) }
    }

    fun htmlToText(html: String): String = decodeEntities(
        html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n\n"),
    )

    fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}
