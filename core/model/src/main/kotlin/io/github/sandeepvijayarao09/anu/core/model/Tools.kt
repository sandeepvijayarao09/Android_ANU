package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * What a model is told about a tool. [parameters] is generated from the tool's input serializer,
 * never hand-written (§7.1).
 */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    init {
        require(NAME.matches(name)) { "Tool names must be snake_case <connector>_<verb>: $name" }
    }

    companion object {
        private val NAME = Regex("[a-z][a-z0-9_]{0,63}")

        fun of(name: String, description: String, input: KSerializer<*>): ToolSpec =
            ToolSpec(name, description, JsonSchemas.of(input))
    }
}

/**
 * A tool call the model asked for. The Tool Broker decides whether it runs (§7.2).
 * [signature] is opaque provider data that must be sent back unchanged with the transcript, such as
 * Gemini's thought signatures. On-device brains leave it null.
 */
data class ToolCall(val name: String, val arguments: JsonObject, val signature: String? = null)
