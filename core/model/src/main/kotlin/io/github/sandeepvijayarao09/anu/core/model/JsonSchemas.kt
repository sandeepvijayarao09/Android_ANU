package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Describes a tool input class or property to the model. It is copied into the generated schema. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class Description(val text: String)

/**
 * Generates JSON Schemas from kotlinx.serialization descriptors, so tool schemas are never written
 * by hand (§7.1). It covers the subset small models handle well: primitives, enums, lists, maps with
 * string keys, and nested classes. Anything else is rejected when the spec is built, not at run time.
 * Properties with default values are optional; all others are required.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchemas {
    private const val MAX_DEPTH = 6

    fun of(serializer: KSerializer<*>): JsonObject = of(serializer.descriptor, depth = 0, description = null)

    private fun of(descriptor: SerialDescriptor, depth: Int, description: String?): JsonObject {
        require(depth <= MAX_DEPTH) { "Schema nests deeper than $MAX_DEPTH at ${descriptor.serialName}" }
        return buildJsonObject {
            when (val kind = descriptor.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> put("type", "string")
                PrimitiveKind.BOOLEAN -> put("type", "boolean")
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                    put("type", "integer")
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
                SerialKind.ENUM -> {
                    put("type", "string")
                    putJsonArray("enum") {
                        for (i in 0 until descriptor.elementsCount) add(JsonPrimitive(descriptor.getElementName(i)))
                    }
                }
                StructureKind.LIST -> {
                    put("type", "array")
                    put("items", of(descriptor.getElementDescriptor(0), depth + 1, description = null))
                }
                StructureKind.MAP -> {
                    require(descriptor.getElementDescriptor(0).kind == PrimitiveKind.STRING) {
                        "Map keys must be strings in ${descriptor.serialName}"
                    }
                    put("type", "object")
                    put("additionalProperties", of(descriptor.getElementDescriptor(1), depth + 1, description = null))
                }
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    put("type", "object")
                    putJsonObject("properties") {
                        for (i in 0 until descriptor.elementsCount) {
                            val property = of(
                                descriptor.getElementDescriptor(i),
                                depth + 1,
                                descriptor.getElementAnnotations(i).description(),
                            )
                            put(descriptor.getElementName(i), property)
                        }
                    }
                    val required = (0 until descriptor.elementsCount).filterNot(descriptor::isElementOptional)
                    if (required.isNotEmpty()) {
                        putJsonArray("required") { required.forEach { add(JsonPrimitive(descriptor.getElementName(it))) } }
                    }
                }
                else -> throw IllegalArgumentException("Unsupported schema kind $kind at ${descriptor.serialName}")
            }
            (description ?: descriptor.annotations.description())?.let { put("description", it) }
        }
    }

    private fun List<Annotation>.description(): String? = filterIsInstance<Description>().firstOrNull()?.text
}
