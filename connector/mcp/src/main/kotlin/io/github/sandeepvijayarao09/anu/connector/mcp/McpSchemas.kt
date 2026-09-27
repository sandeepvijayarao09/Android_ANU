package io.github.sandeepvijayarao09.anu.connector.mcp

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** What Anu does with the JSON Schemas MCP servers send (ADR-046). */
internal object McpSchemas {
    private const val MAX_DEPTH = 6
    private const val MAX_DESCRIPTION = 200
    private val TYPES = setOf("string", "integer", "number", "boolean", "array", "object")
    private val TCHAR = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private const val SENTINEL_START = "=?base64?"
    private const val SENTINEL_END = "?="

    /**
     * The schema the model is shown: only the parts Anu's own tool schemas use (type, description,
     * properties, required, items, enum, additionalProperties), so every runtime's tool format accepts
     * it. Descriptions are cut short and stripped of markup: the server wrote them.
     */
    fun forModel(schema: JsonObject, depth: Int = 0): JsonObject = buildJsonObject {
        val type = typeOf(schema)
        put("type", JsonPrimitive(type))
        schema["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", JsonPrimitive(McpText.clean(it, MAX_DESCRIPTION))) }
        (schema["enum"] as? JsonArray)?.takeIf { values -> values.all { it is JsonPrimitive && it !is JsonNull } }?.let { put("enum", it) }
        if (depth >= MAX_DEPTH) return@buildJsonObject
        when (type) {
            "object" -> {
                val properties = schema["properties"] as? JsonObject
                if (properties != null) {
                    put("properties", buildJsonObject {
                        properties.forEach { (name, value) -> (value as? JsonObject)?.let { put(name, forModel(it, depth + 1)) } }
                    })
                    val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it in properties }
                    if (!required.isNullOrEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
                } else {
                    (schema["additionalProperties"] as? JsonObject)?.let { put("additionalProperties", forModel(it, depth + 1)) }
                }
            }
            "array" -> (schema["items"] as? JsonObject)?.let { put("items", forModel(it, depth + 1)) }
        }
    }

    /**
     * Checks [arguments] against the server's schema before anything is approved or sent (I6): required
     * fields, primitive types, enums, and unknown fields when the schema forbids them.
     */
    fun validate(schema: JsonObject, arguments: JsonObject) = checkObject(schema, arguments, path = "")

    private fun checkObject(schema: JsonObject, value: JsonObject, path: String) {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        required.filter { it !in value || value[it] is JsonNull }.forEach { throw IllegalArgumentException("${path}$it is required") }
        if (schema["additionalProperties"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == false) {
            value.keys.firstOrNull { it !in properties }?.let { throw IllegalArgumentException("$path$it isn't a parameter of this tool") }
        }
        value.forEach { (name, element) ->
            val property = properties[name] as? JsonObject ?: return@forEach
            checkValue(property, element, "$path$name")
        }
    }

    private fun checkValue(schema: JsonObject, value: JsonElement, path: String) {
        if (value is JsonNull) return
        val type = declaredTypes(schema)
        if (type.isNotEmpty() && type.none { matches(it, value) }) throw IllegalArgumentException("$path must be ${type.joinToString(" or ")}")
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) throw IllegalArgumentException("$path must be one of ${allowed.joinToString { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }}")
        }
        when (value) {
            is JsonObject -> checkObject(schema, value, "$path.")
            is JsonArray -> (schema["items"] as? JsonObject)?.let { items -> value.forEachIndexed { index, item -> checkValue(items, item, "$path[$index]") } }
            else -> Unit
        }
    }

    private fun matches(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { it % 1.0 == 0.0 } == true
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "null" -> value is JsonNull
        else -> true
    }

    /**
     * What the user may change on the approval sheet (§7.2): top-level text properties, but not fixed
     * choices (`enum`, `const`) or machine formats such as dates. The server wrote the labels, so they
     * are cleaned like everything else it says.
     */
    fun editable(schema: JsonObject): List<EditableField> {
        val properties = schema["properties"] as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
        return properties.mapNotNull { (name, value) ->
            val property = value as? JsonObject
            if (property == null || "string" !in declaredTypes(property) || listOf("enum", "const", "format").any { it in property }) return@mapNotNull null
            val title = (property["title"] as? JsonPrimitive)?.contentOrNull?.let { McpText.clean(it, 40) }?.takeIf(String::isNotBlank)
            EditableField(name, title ?: label(name), EditableField.Kind.TEXT, optional = name !in required)
        }
    }

    /** "note_text" and "noteText" read as "Note text". */
    private fun label(name: String): String =
        name.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

    private fun declaredTypes(schema: JsonObject): List<String> = when (val type = schema["type"]) {
        is JsonPrimitive -> listOfNotNull(type.contentOrNull)
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    /** One type for the model: the first declared one that isn't null, or a guess from the shape. */
    private fun typeOf(schema: JsonObject): String = declaredTypes(schema).firstOrNull { it in TYPES } ?: when {
        "properties" in schema || "additionalProperties" in schema -> "object"
        "items" in schema -> "array"
        else -> "string"
    }

    /**
     * Why the schema's `x-mcp-header` annotations break MCP's rules, or null if they don't. A tool whose
     * annotations are invalid must be left out.
     */
    fun headerProblem(schema: JsonObject): String? {
        val all = mutableListOf<List<String>>()
        collect(schema, emptyList(), all)
        val reachable = headerProperties(schema)
        val names = mutableSetOf<String>()
        for (path in all) {
            val location = path.dropLast(1)
            val property = reachable.firstOrNull { it.path == location } ?: return "x-mcp-header at ${path.joinToString("/")} isn't reachable through properties"
            if (!TCHAR.matches(property.header)) return "x-mcp-header \"${property.header}\" isn't a valid header name"
            if (!names.add(property.header.lowercase())) return "x-mcp-header \"${property.header}\" is used twice"
            if (property.type !in setOf("string", "integer", "boolean")) return "x-mcp-header is on a ${property.type ?: "untyped"} parameter"
        }
        return null
    }

    /** Every `x-mcp-header` key anywhere in the schema, as the path to it. */
    private fun collect(element: JsonElement, path: List<String>, into: MutableList<List<String>>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "x-mcp-header") into += path + key else collect(value, path + key, into)
            }
            is JsonArray -> element.forEachIndexed { index, value -> collect(value, path + index.toString(), into) }
            else -> Unit
        }
    }

    private data class HeaderProperty(val path: List<String>, val argumentPath: List<String>, val header: String, val type: String?)

    /** Annotated properties reached through `properties` alone, the only place MCP allows them. */
    private fun headerProperties(schema: JsonObject, path: List<String> = emptyList(), arguments: List<String> = emptyList()): List<HeaderProperty> {
        val properties = schema["properties"] as? JsonObject ?: return emptyList()
        return properties.flatMap { (name, value) ->
            val property = value as? JsonObject ?: return@flatMap emptyList()
            val here = path + listOf("properties", name)
            val header = (property["x-mcp-header"] as? JsonPrimitive)?.contentOrNull
            listOfNotNull(header?.let { HeaderProperty(here, arguments + name, it, declaredTypes(property).singleOrNull()) }) +
                headerProperties(property, here, arguments + name)
        }
    }

    /** `Mcp-Param-{Name}` headers for the annotated parameters present in [arguments]. */
    fun paramHeaders(schema: JsonObject, arguments: JsonObject): Map<String, String> = headerProperties(schema).mapNotNull { property ->
        var value: JsonElement? = arguments
        for (key in property.argumentPath) value = (value as? JsonObject)?.get(key)
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        if (primitive is JsonNull) return@mapNotNull null
        val text = when {
            primitive.isString -> primitive.content
            primitive.booleanOrNull != null -> primitive.booleanOrNull.toString()
            else -> primitive.longOrNull?.toString() ?: primitive.content
        }
        "Mcp-Param-${property.header}" to headerValue(text)
    }.toMap()

    /** A header value as MCP requires: plain when it is safe ASCII, otherwise the Base64 sentinel form. */
    fun headerValue(value: String): String {
        val safe = value.isNotEmpty() && value.all { it.code in 0x20..0x7E } && value == value.trim() &&
            !(value.startsWith(SENTINEL_START) && value.endsWith(SENTINEL_END))
        return if (safe) value else SENTINEL_START + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + SENTINEL_END
    }
}

/** Text from a server, made fit for a prompt or a screen: no markup, no control characters, and short. */
internal object McpText {
    private val MARKUP = Regex("[<>`]")
    private val SPACE = Regex("\\s+")

    fun clean(text: String, max: Int): String {
        val plain = SPACE.replace(MARKUP.replace(text.filter { !it.isISOControl() || it == '\n' }, " "), " ").trim()
        return if (plain.length <= max) plain else plain.take(max - 1).trimEnd() + "…"
    }
}
