package io.github.sandeepvijayarao09.anu.brain.ollama

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The Mac answered with an error. The message never contains the token. */
class MacBrainException(val code: Int, message: String) : IOException("Mac $code: $message")

/**
 * Gemma on the user's own Mac, through Ollama behind `scripts/mac-brain.py` (ARCHITECTURE §5, ADR-111). It is the top
 * tier when paired, in Gemini's place, and it is off the phone, so every rule for the cloud holds:
 * - **Egress.** Every request goes through [EgressGate]: redacted, and ledgered with the Mac as its destination (I3).
 *   Photos, voice notes, screens and PDFs never reach it (MEDIA-1).
 * - **Token.** A vault handle. The vault's injector adds it as a bearer token for the paired host only, over HTTPS
 *   pinned to the Mac's own certificate ([pinnedTo]).
 * - **Tools.** Reported, never run (ADR-017). The reasoning, when asked for, is dropped like Gemini's.
 */
class OllamaBrain(
    private val baseUrl: HttpUrl,
    private val token: SecretRef,
    private val http: OkHttpClient,
    private val egress: EgressGate,
    contextTokens: Int = DEFAULT_CONTEXT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Brain {

    override val id = BrainId.CLOUD_PRO

    override val capabilities = BrainCapabilities(
        location = BrainLocation.CLOUD,
        maxContextTokens = contextTokens,
        toolCalling = true,
        structuredOutput = true,
    )

    override fun stream(request: BrainRequest): Flow<BrainEvent> = callbackFlow {
        val prepared = egress.prepare(request, destination = baseUrl.host)
        val call = http.newCall(httpRequest(OllamaWire.body(prepared.request)))
        launch(dispatcher) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw MacBrainException(response.code, response.body.string().take(300))
                    val restorer = prepared.redaction.streamRestorer()
                    val calls = mutableListOf<ToolCall>()
                    var usage: BrainEvent.Usage? = null
                    val source = response.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        val chunk = json.parseToJsonElement(line).jsonObject
                        chunk["error"]?.let { throw MacBrainException(response.code, it.jsonPrimitive.content) }
                        val message = chunk["message"] as? JsonObject
                        (message?.get("content") as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty)?.let { text ->
                            restorer.push(text).takeIf(String::isNotEmpty)?.let { send(BrainEvent.TextDelta(it)) }
                        }
                        (message?.get("tool_calls") as? JsonArray)?.forEach { element ->
                            val function = element.jsonObject["function"]?.jsonObject ?: return@forEach
                            val toolCall = ToolCall(
                                name = function.getValue("name").jsonPrimitive.content,
                                arguments = prepared.redaction.restore(function["arguments"] as? JsonObject ?: JsonObject(emptyMap())),
                            )
                            calls += toolCall
                            send(BrainEvent.ToolCallRequested(toolCall))
                        }
                        if ((chunk["done"] as? JsonPrimitive)?.booleanOrNull == true) usage = OllamaWire.usage(chunk)
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
        .url(baseUrl.newBuilder().addPathSegments("api/chat").build())
        .post(body.toString().toRequestBody(JSON))
        .tag(SecretUse::class.java, SecretUse(token, header = "Authorization", caller = CALLER, scheme = "Bearer"))
        .build()

    companion object {
        /** The name the vault item must list in `allowedUsers`. */
        const val CALLER = "brain:ollama"

        /** The context `scripts/mac-brain.py` gives the model by default: Gemma 4 26B fits it on a 24 GB Mac. */
        const val DEFAULT_CONTEXT = 32_768
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
