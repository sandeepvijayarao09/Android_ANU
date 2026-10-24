package io.github.sandeepvijayarao09.anu.brain.ollama

import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.factsBlock
import io.github.sandeepvijayarao09.anu.core.model.userText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Translates Anu's brain contract to Ollama's `/api/chat` JSON and back. Ollama applies the model's own chat template
 * and tool format (I8); tool and response schemas are sent as JSON Schema, so `core:model` schemas pass unchanged.
 */
internal object OllamaWire {

    /** Saved with the answer, so "About this answer" says it ran on the Mac (ADR-111). */
    const val BACKEND = "MAC"

    /**
     * Like Gemini's (ADR-111): [BrainRequest.memory], [BrainRequest.relevant] and [BrainRequest.onDeviceNote] are never
     * sent, since the Mac is off the phone. Only [BrainRequest.sharedFacts], which the user saw on the consent sheet and
     * the egress gate has already redacted, go with the newest message (ADR-054). The model and its context size are
     * the Mac's to choose: `scripts/mac-brain.py` sets both.
     */
    fun body(request: BrainRequest): JsonObject = buildJsonObject {
        put("stream", true)
        putJsonArray("messages") {
            request.system?.let { system -> addJsonObject { put("role", "system"); put("content", system) } }
            request.transcript.indices.forEach { index -> messages(request, index).forEach { add(it) } }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    }
                }
            }
        }
        request.responseSchema?.let { put("format", it) }
        // Only when asked: a model without thinking refuses the field.
        if (request.thinking) put("think", true)
        putJsonObject("options") {
            put("temperature", request.sampling.temperature)
            put("top_p", request.sampling.topP)
            put("top_k", request.sampling.topK)
            put("seed", request.sampling.seed)
            request.maxOutputTokens?.let { put("num_predict", it) }
        }
    }

    /** Ollama's final chunk carries the totals and its own timings in nanoseconds. */
    fun usage(done: JsonObject): BrainEvent.Usage {
        val input = done["prompt_eval_count"].int()
        val output = done["eval_count"].int()
        return BrainEvent.Usage(
            contextTokens = (input ?: 0) + (output ?: 0),
            inputTokens = input,
            outputTokens = output,
            backend = BACKEND,
            readMs = done["prompt_eval_duration"].long()?.let { it / 1_000_000 },
            writeMs = done["eval_duration"].long()?.let { it / 1_000_000 },
            counted = input != null && output != null,
        )
    }

    private fun messages(request: BrainRequest, index: Int): List<JsonObject> = when (val message = request.transcript[index]) {
        is ChatMessage.User -> listOf(
            buildJsonObject {
                put("role", "user")
                put("content", request.userText(index, facts = factsBlock(request.sharedFacts)))
            },
        )
        is ChatMessage.Model -> listOf(
            buildJsonObject {
                put("role", "assistant")
                put("content", message.text)
                if (message.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        message.toolCalls.forEach { call ->
                            addJsonObject {
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                }
                            }
                        }
                    }
                }
            },
        )
        // One message per result, named, so the template pairs each with its call.
        is ChatMessage.Tool -> message.results.map { result ->
            buildJsonObject {
                put("role", "tool")
                put("tool_name", result.toolName)
                put("content", (result.result as? JsonPrimitive)?.takeIf { it.isString }?.content ?: result.result.toString())
            }
        }
    }

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
}
