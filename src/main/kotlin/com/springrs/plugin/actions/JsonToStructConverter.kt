package com.springrs.plugin.actions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.GsonBuilder
import com.springrs.plugin.utils.RustTypeUtils

/**
 * JSON to Rust struct converter.
 *
 * Supports:
 * - nested objects -> nested structs
 * - arrays -> Vec<T>
 * - null -> Option<T>
 * - camelCase -> snake_case + optional serde rename (only when needed)
 */
object JsonToStructConverter {

    data class ConvertOptions(
        val serdeDerive: Boolean = true,
        val debugDerive: Boolean = true,
        val cloneDerive: Boolean = false,
        val addRenameMacro: Boolean = false,
        val addOptionT: Boolean = true,
        val publicStruct: Boolean = true,
        val publicField: Boolean = true,
        val addValueComments: Boolean = false
    )

    private enum class ImportKey {
        SERDE_TRAITS,
        SERDE_JSON_VALUE
    }

    private val IMPORTS_BY_KEY: Map<ImportKey, List<String>> = mapOf(
        ImportKey.SERDE_TRAITS to listOf(
            "use serde::Serialize;",
            "use serde::Deserialize;"
        ),
        ImportKey.SERDE_JSON_VALUE to listOf("use serde_json::Value;")
    )

    private val JSON_VALUE_TYPE_REGEX =
        Regex("""\b${Regex.escape(RustTypeUtils.JsonTypes.VALUE)}\b""")

    /**
     * Convert a JSON string into Rust struct definitions.
     */
    fun convert(json: String, rootStructName: String = "Root", options: ConvertOptions = ConvertOptions()): String {
        val trimmedJson = json.trim()
        if (trimmedJson.isEmpty()) return ""

        return try {
            val element = JsonParser.parseString(trimmedJson)
            val structs = mutableListOf<String>()
            val generatedNames = mutableSetOf<String>()

            when {
                element.isJsonObject -> {
                    generateStruct(element.asJsonObject, rootStructName, options, structs, generatedNames)
                }
                element.isJsonArray -> {
                    val array = element.asJsonArray
                    if (array.size() > 0 && array[0].isJsonObject) {
                        generateStruct(array[0].asJsonObject, rootStructName, options, structs, generatedNames)
                    }
                }
                else -> return "// JSON must be an object or array"
            }

            structs.reversed().joinToString("\n\n")
        } catch (e: Exception) {
            "// JSON parse error: ${e.message}"
        }
    }

    /**
     * Format a JSON string.
     */
    fun formatJson(json: String): String {
        return try {
            val element = JsonParser.parseString(json.trim())
            GsonBuilder().setPrettyPrinting().create().toJson(element)
        } catch (e: Exception) {
            json
        }
    }

    /**
     * Generate required `use` statements based on options and generated code.
     */
    fun getRequiredImports(options: ConvertOptions, rustCode: String): List<String> {
        val required = linkedSetOf<ImportKey>()

        if (options.serdeDerive) {
            required.add(ImportKey.SERDE_TRAITS)
        }

        if (JSON_VALUE_TYPE_REGEX.containsMatchIn(rustCode)) {
            required.add(ImportKey.SERDE_JSON_VALUE)
        }

        return required
            .flatMap { IMPORTS_BY_KEY[it].orEmpty() }
            .distinct()
    }

    // ==================== Struct Generation ====================

    private fun generateStruct(
        obj: JsonObject,
        structName: String,
        options: ConvertOptions,
        structs: MutableList<String>,
        generatedNames: MutableSet<String>
    ): String {
        val uniqueName = getUniqueName(structName, generatedNames)
        generatedNames.add(uniqueName)

        val sb = StringBuilder()

        // derive macro
        buildDeriveAttribute(options)?.let { sb.appendLine(it) }

        // struct declaration
        val visibility = RustTypeUtils.visibilityPrefix(options.publicStruct)
        sb.appendLine("${visibility}struct $uniqueName {")

        // fields
        val usedFieldNames = mutableSetOf<String>()
        for ((key, value) in obj.entrySet()) {
            buildField(key, value, options, structs, generatedNames, usedFieldNames, sb)
        }

        sb.append("}")
        structs.add(sb.toString())

        return uniqueName
    }

    /**
     * Build derive attribute.
     */
    private fun buildDeriveAttribute(options: ConvertOptions): String? {
        val derives = buildList {
            if (options.serdeDerive) {
                add(RustTypeUtils.Derives.SERIALIZE)
                add(RustTypeUtils.Derives.DESERIALIZE)
            }
            if (options.debugDerive) add(RustTypeUtils.Derives.DEBUG)
            if (options.cloneDerive) add(RustTypeUtils.Derives.CLONE)
        }

        return if (derives.isNotEmpty()) {
            RustTypeUtils.SerdeAttrs.derive(*derives.toTypedArray())
        } else null
    }

    /**
     * Build a single field.
     */
    private fun buildField(
        key: String,
        value: JsonElement,
        options: ConvertOptions,
        structs: MutableList<String>,
        generatedNames: MutableSet<String>,
        usedFieldNames: MutableSet<String>,
        sb: StringBuilder
    ) {
        val baseFieldName = normalizeRustFieldName(key)
        val uniqueBaseFieldName = getUniqueFieldName(baseFieldName, usedFieldNames)
        usedFieldNames.add(uniqueBaseFieldName)

        val isKeyword = RustTypeUtils.isKeyword(uniqueBaseFieldName)
        val fieldName = if (isKeyword) "r#$uniqueBaseFieldName" else uniqueBaseFieldName
        val fieldVisibility = RustTypeUtils.visibilityPrefix(options.publicField)

        // Value comment.
        if (options.addValueComments) {
            sb.appendLine(RustTypeUtils.Codegen.docExample(getValuePreview(value)))
        }

        // serde rename (only when needed)
        val needsRename = key != uniqueBaseFieldName
        if (options.addRenameMacro && needsRename) {
            sb.appendLine("${RustTypeUtils.Codegen.INDENT}${RustTypeUtils.SerdeAttrs.rename(key)}")
        }

        // Type.
        val rustType = inferRustType(value, uniqueBaseFieldName, options, structs, generatedNames)
        val finalType = if (options.addOptionT && !RustTypeUtils.isOptionType(rustType)) {
            RustTypeUtils.optionOf(rustType)
        } else {
            rustType
        }

        sb.appendLine("${RustTypeUtils.Codegen.INDENT}$fieldVisibility$fieldName: $finalType,")
    }

    // ==================== Type Inference ====================

    private fun inferRustType(
        element: JsonElement,
        fieldName: String,
        options: ConvertOptions,
        structs: MutableList<String>,
        generatedNames: MutableSet<String>
    ): String {
        return when {
            element.isJsonNull -> RustTypeUtils.JsonTypes.VALUE
            element.isJsonPrimitive -> inferPrimitiveType(element.asJsonPrimitive)
            element.isJsonArray -> {
                val elementType = inferArrayElementType(element.asJsonArray, fieldName, options, structs, generatedNames)
                "${RustTypeUtils.VEC}<$elementType>"
            }
            element.isJsonObject -> {
                val nestedStructName = fieldNameToStructName(fieldName)
                generateStruct(element.asJsonObject, nestedStructName, options, structs, generatedNames)
            }
            else -> RustTypeUtils.JsonTypes.VALUE
        }
    }

    private fun inferPrimitiveType(primitive: JsonPrimitive): String {
        return when {
            primitive.isBoolean -> RustTypeUtils.JsonTypes.BOOL
            primitive.isNumber -> {
                val str = primitive.asNumber.toString()
                if (str.contains('.') || str.contains('e') || str.contains('E')) {
                    RustTypeUtils.JsonTypes.F64
                } else {
                    RustTypeUtils.JsonTypes.I64
                }
            }
            primitive.isString -> RustTypeUtils.JsonTypes.STRING
            else -> RustTypeUtils.JsonTypes.STRING
        }
    }

    private fun inferArrayElementType(
        array: JsonArray,
        fieldName: String,
        options: ConvertOptions,
        structs: MutableList<String>,
        generatedNames: MutableSet<String>
    ): String {
        if (array.size() == 0) return RustTypeUtils.JsonTypes.VALUE

        val firstElement = array[0]
        return when {
            firstElement.isJsonNull -> RustTypeUtils.JsonTypes.VALUE
            firstElement.isJsonPrimitive -> inferPrimitiveType(firstElement.asJsonPrimitive)
            firstElement.isJsonObject -> {
                val singularName = singularize(fieldNameToStructName(fieldName))
                generateStruct(firstElement.asJsonObject, singularName, options, structs, generatedNames)
            }
            firstElement.isJsonArray -> {
                val innerType = inferArrayElementType(firstElement.asJsonArray, fieldName, options, structs, generatedNames)
                "${RustTypeUtils.VEC}<$innerType>"
            }
            else -> RustTypeUtils.JsonTypes.VALUE
        }
    }

    // ==================== Naming Utilities ====================

    private fun normalizeRustFieldName(originalKey: String): String {
        val snake = camelToSnake(originalKey)

        val sanitized = buildString {
            for (ch in snake) {
                when {
                    ch in 'a'..'z' || ch in '0'..'9' || ch == '_' -> append(ch)
                    ch in 'A'..'Z' -> append(ch.lowercaseChar())
                    else -> append('_')
                }
            }
        }.replace(Regex("_+"), "_")

        val base = if (sanitized.isEmpty() || sanitized.all { it == '_' }) "field" else sanitized
        return if (base.first().isDigit()) "_$base" else base
    }

    /**
     * Convert a field name to a struct name (PascalCase).
     */
    private fun fieldNameToStructName(fieldName: String): String {
        val normalized = normalizeRustFieldName(fieldName).trimStart('_')

        val pascalName = normalized
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString("") { part ->
                val safePart = if (part.first().isDigit()) "N$part" else part
                safePart.replaceFirstChar { c -> c.uppercaseChar() }
            }

        val safeName = if (pascalName.isEmpty()) "Nested" else pascalName
        val keywordSafeName = if (RustTypeUtils.isKeyword(safeName) || RustTypeUtils.isKeyword(safeName.lowercase())) {
            "${safeName}Data"
        } else {
            safeName
        }

        return if (keywordSafeName.first().isDigit()) "Nested$keywordSafeName" else keywordSafeName
    }

    /**
     * Convert camelCase to snake_case.
     */
    private fun camelToSnake(str: String): String {
        return buildString {
            str.forEachIndexed { index, char ->
                if (char.isUpperCase()) {
                    if (index > 0) append('_')
                    append(char.lowercaseChar())
                } else {
                    append(char)
                }
            }
        }
    }

    /**
     * A very small English plural-to-singular helper.
     */
    private fun singularize(word: String): String {
        return when {
            word.endsWith("ies") -> word.dropLast(3) + "y"
            word.endsWith("es") && (word.endsWith("sses") || word.endsWith("shes") || word.endsWith("ches") || word.endsWith("xes")) -> word.dropLast(2)
            word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }
    }

    /**
     * Get a unique struct name.
     */
    private fun getUniqueName(baseName: String, existingNames: Set<String>): String {
        if (baseName !in existingNames) return baseName
        var counter = 2
        while ("$baseName$counter" in existingNames) counter++
        return "$baseName$counter"
    }

    private fun getUniqueFieldName(baseName: String, existingNames: Set<String>): String {
        if (baseName !in existingNames) return baseName
        var counter = 2
        while ("${baseName}_$counter" in existingNames) counter++
        return "${baseName}_$counter"
    }

    /**
     * Get preview text for a JSON value (used in generated comments).
     */
    private fun getValuePreview(element: JsonElement): String {
        return when {
            element.isJsonNull -> "null"
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isString -> {
                        val str = primitive.asString
                        if (str.length > 50) str.substring(0, 47) + "..." else str
                    }
                    primitive.isNumber -> primitive.asNumber.toString()
                    primitive.isBoolean -> primitive.asBoolean.toString()
                    else -> primitive.toString()
                }
            }
            element.isJsonArray -> {
                val size = element.asJsonArray.size()
                if (size == 0) "[]" else "[$size items]"
            }
            element.isJsonObject -> "{${element.asJsonObject.size()} fields}"
            else -> element.toString()
        }
    }
}
