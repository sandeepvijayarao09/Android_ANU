package io.github.sandeepvijayarao09.anu.connector.gmail

import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant

/** The Gmail scopes Anu asks for at Connect (ADR-029). */
object GmailScopes {
    const val READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    const val COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
    const val SEND = "https://www.googleapis.com/auth/gmail.send"
    val ALL = setOf(READONLY, COMPOSE, SEND)
}

class GmailNotConnectedException : IOException("Gmail isn't connected. Connect it in Settings.")

class GmailException(val code: Int, message: String) : IOException("Gmail $code: $message")

/** Short-lived access tokens from Google Play services. Anu never stores them (ADR-029). */
interface AccessTokens {
    /** Throws [GmailNotConnectedException] until the user has connected Gmail. */
    suspend fun token(scopes: Set<String>): String

    /** Forgets a token the server rejected, so the next call gets a fresh one. */
    suspend fun invalidate(token: String) {}
}

/** A token for one request, read only by [GmailAuthInterceptor]. Its string form hides the value. */
internal class BearerToken(val value: String) {
    override fun toString() = "BearerToken(***)"
}

/**
 * Attaches the access token only for Gmail's host, over HTTPS (ADR-029). It is a network interceptor,
 * so it checks the host of the hop actually sent. Use it through [gmailHttpClient], which also turns
 * redirects off.
 */
class GmailAuthInterceptor internal constructor(private val host: String, private val requireHttps: Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = request.tag(BearerToken::class.java) ?: return chain.proceed(request)
        if (request.url.host != host || (requireHttps && !request.isHttps)) {
            throw IOException("A Gmail token may only go to https://$host")
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer ${token.value}").build())
    }
}

const val GMAIL_HOST = "gmail.googleapis.com"

fun gmailHttpClient(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
    builder.followRedirects(false).followSslRedirects(false).addNetworkInterceptor(GmailAuthInterceptor(GMAIL_HOST, requireHttps = true)).build()

internal fun testGmailHttpClient(host: String): OkHttpClient =
    OkHttpClient.Builder().followRedirects(false).addNetworkInterceptor(GmailAuthInterceptor(host, requireHttps = false)).build()

data class MessageSummary(val id: String, val threadId: String, val from: String, val subject: String, val date: String, val snippet: String)

data class ThreadMessage(
    val id: String,
    val from: String,
    val to: String,
    val cc: String,
    val subject: String,
    val date: String,
    /** The RFC 5322 Message-ID, used to thread replies. */
    val messageId: String?,
    val text: String,
)

data class GmailThread(val id: String, val messages: List<ThreadMessage>) {
    val subject: String get() = messages.firstOrNull()?.subject.orEmpty()
}

/**
 * The Gmail REST API for the signed-in user (ARCHITECTURE §9).
 * - A 401 gets one fresh token and one retry.
 * - Every request that carries the user's content (a query, a draft, a send) writes a ledger entry. It is
 *   not redacted (ADR-030).
 */
class GmailClient(
    private val http: OkHttpClient,
    private val tokens: AccessTokens,
    private val ledger: PrivacyLedger?,
    private val baseUrl: HttpUrl = "https://$GMAIL_HOST/gmail/v1/users/me/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) {

    suspend fun search(query: String, max: Int): List<MessageSummary> {
        record("email search", query.length)
        val url = baseUrl.newBuilder().addPathSegment("messages").addQueryParameter("q", query).addQueryParameter("maxResults", max.toString()).build()
        val ids = get(url, GmailScopes.READONLY)["messages"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("id").jsonPrimitive.content }
        return ids.map { id ->
            val message = get(
                baseUrl.newBuilder().addPathSegment("messages").addPathSegment(id)
                    .addQueryParameter("format", "metadata")
                    .apply { listOf("From", "Subject", "Date").forEach { addQueryParameter("metadataHeaders", it) } }
                    .build(),
                GmailScopes.READONLY,
            )
            val headers = GmailParsing.headers(message)
            MessageSummary(
                id = id,
                threadId = message.getValue("threadId").jsonPrimitive.content,
                from = headers["from"].orEmpty(),
                subject = headers["subject"].orEmpty(),
                date = headers["date"].orEmpty(),
                snippet = GmailParsing.decodeEntities(message["snippet"]?.jsonPrimitive?.content.orEmpty()),
            )
        }
    }

    suspend fun thread(id: String): GmailThread {
        val json = get(baseUrl.newBuilder().addPathSegment("threads").addPathSegment(id).addQueryParameter("format", "full").build(), GmailScopes.READONLY)
        return GmailThread(id, json["messages"]?.jsonArray.orEmpty().map { GmailParsing.message(it.jsonObject) })
    }

    /** Returns the new draft's id. */
    suspend fun createDraft(rawMime: String, threadId: String?): String {
        record("email draft", rawMime.length)
        val body = buildJsonObject {
            put("message", buildJsonObject {
                put("raw", Mime.base64Url(rawMime))
                threadId?.let { put("threadId", it) }
            })
        }
        return post(baseUrl.newBuilder().addPathSegment("drafts").build(), body, GmailScopes.COMPOSE).getValue("id").jsonPrimitive.content
    }

    /** Returns the sent message's id. */
    suspend fun send(rawMime: String, threadId: String?): String {
        record("email send", rawMime.length)
        val body = buildJsonObject {
            put("raw", Mime.base64Url(rawMime))
            threadId?.let { put("threadId", it) }
        }
        return post(baseUrl.newBuilder().addPathSegments("messages/send").build(), body, GmailScopes.SEND).getValue("id").jsonPrimitive.content
    }

    private suspend fun get(url: HttpUrl, scope: String): JsonObject = call(scope) { Request.Builder().url(url).get() }

    private suspend fun post(url: HttpUrl, body: JsonObject, scope: String): JsonObject =
        call(scope) { Request.Builder().url(url).post(body.toString().toRequestBody(JSON)) }

    private suspend fun call(scope: String, build: () -> Request.Builder): JsonObject = withContext(io) {
        var token = tokens.token(setOf(scope))
        repeat(2) { attempt ->
            val response = http.newCall(build().tag(BearerToken::class.java, BearerToken(token)).build()).execute()
            response.use {
                if (it.code == 401 && attempt == 0) {
                    tokens.invalidate(token)
                    token = tokens.token(setOf(scope))
                    return@repeat
                }
                val text = it.body.string()
                if (!it.isSuccessful) throw GmailException(it.code, text.take(300))
                return@withContext json.parseToJsonElement(text).jsonObject
            }
        }
        throw GmailException(401, "the token was refused twice")
    }

    private suspend fun record(purpose: String, characters: Int) {
        ledger?.record(LedgerEntry(clock(), GMAIL_HOST, purpose, setOf(DataClass.PERSONAL), emptyMap(), characters))
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}
