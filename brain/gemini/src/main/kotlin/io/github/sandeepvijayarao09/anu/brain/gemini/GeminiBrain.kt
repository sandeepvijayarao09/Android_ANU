package io.github.sandeepvijayarao09.anu.brain.gemini

import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.security.EgressGate
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.security.SecretUse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The Gemini API returned an error or blocked the prompt. The message never contains the API key. */
class GeminiException(val code: Int, message: String) : IOException("Gemini $code: $message")

/**
 * Gemini through the REST API with server-sent events (ARCHITECTURE §5, Cloud tier).
 * - **Egress.** Every request goes through [EgressGate] first: it is redacted and ledgered (I3).
 *   Placeholders in the reply, including inside tool arguments, are restored on the device.
 * - **Key.** The API key is a vault handle. The vault's injector adds it as `x-goog-api-key` for this
 *   host only. It is never put in the URL (§18).
 * - **Tools.** Tool calls are reported, never run (ADR-017). Gemini's thought signatures are kept on
 *   [ToolCall.signature] and sent back with the transcript.
 * - **Sampling.** Gemini's own recommended sampling is used; only output length and schema are set.
 */
class GeminiBrain(
    override val id: BrainId,
    private val model: String,
    private val apiKey: SecretRef,
    private val http: OkHttpClient,
    private val egress: EgressGate,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Brain {

    init {
        require(id == BrainId.CLOUD_FLASH || id == BrainId.CLOUD_PRO) { "GeminiBrain is a cloud brain, not $id" }
    }

    override val capabilities = BrainCapabilities(
        location = BrainLocation.CLOUD,
        maxContextTokens = 1_000_000,
        toolCalling = true,
        structuredOutput = true,
    )

    override fun stream(request: BrainRequest): Flow<BrainEvent> = callbackFlow {
        val prepared = egress.prepare(request, destination = baseUrl.host)
        val call = http.newCall(httpRequest(GeminiWire.body(prepared.request)))
        launch(dispatcher) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw GeminiException(response.code, response.body.string().take(300))
                    val restorer = prepared.redaction.streamRestorer()
                    val calls = mutableListOf<ToolCall>()
                    var usage: BrainEvent.Usage? = null
                    val source = response.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val chunk = json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                        chunk["promptFeedback"]?.jsonObject?.get("blockReason")?.let {
                            throw GeminiException(response.code, "prompt blocked: ${it.jsonPrimitive.content}")
                        }
                        // Gemini repeats running totals on every chunk. Keep the last and emit it once (§18).
                        chunk["usageMetadata"]?.jsonObject?.let { usage = GeminiWire.usage(it) }
                        val parts = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                        for (element in parts) {
                            val part = element.jsonObject
                            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) continue
                            part["text"]?.jsonPrimitive?.content?.let { text ->
                                restorer.push(text).takeIf(String::isNotEmpty)?.let { send(BrainEvent.TextDelta(it)) }
                            }
                            part["functionCall"]?.jsonObject?.let { function ->
                                val toolCall = ToolCall(
                                    name = function.getValue("name").jsonPrimitive.content,
                                    arguments = prepared.redaction.restore(function["args"]?.jsonObject ?: JsonObject(emptyMap())),
                                    signature = part["thoughtSignature"]?.jsonPrimitive?.content,
                                )
                                calls += toolCall
                                send(BrainEvent.ToolCallRequested(toolCall))
                            }
                        }
                    }
                    restorer.flush().takeIf(String::isNotEmpty)?.let { send(BrainEvent.TextDelta(it)) }
                    send(usage ?: BrainEvent.Usage(contextTokens = 0))
                    send(BrainEvent.Finished(if (calls.isEmpty()) StopReason.COMPLETE else StopReason.TOOL_CALLS))
                }
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        // Collector gone: cancelling the call unblocks the reader, which is otherwise stuck in socket IO (I7).
        awaitClose { call.cancel() }
    }

    private fun httpRequest(body: JsonObject): Request = Request.Builder()
        .url(
            baseUrl.newBuilder()
                .addPathSegments("v1beta/models")
                .addPathSegment("$model:streamGenerateContent")
                .addQueryParameter("alt", "sse")
                .build(),
        )
        .post(body.toString().toRequestBody(JSON))
        .tag(SecretUse::class.java, SecretUse(apiKey, header = "x-goog-api-key", caller = CALLER))
        .build()

    companion object {
        /** The name the vault item must list in `allowedUsers`. */
        const val CALLER = "brain:gemini"
        val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/".toHttpUrl()
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
