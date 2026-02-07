package com.springrs.plugin.routes

import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsValueParameter
import org.rust.lang.core.psi.ext.name

/**
 * Parses spring-rs/axum handler function signatures to extract parameter
 * and return type information for display in the detail panel.
 *
 * Recognizes extractor patterns:
 * - Path<T>        — URL path parameters
 * - Query<T>       — URL query parameters
 * - Json<T>        — JSON request body
 * - Component<T>   — Injected component
 * - Config<T>      — Injected configuration
 * - State<T>       — Application state
 * - HeaderMap      — Request headers
 * - StreamKey      — Stream topic key
 * - Other          — Unknown/custom extractors
 */
object SpringRsHandlerParser {

    /**
     * Parsed handler parameter.
     */
    data class HandlerParam(
        val name: String,           // Parameter binding name (e.g., "id", "body")
        val extractorType: String,  // Extractor kind (e.g., "Path", "Json", "Component")
        val innerType: String,      // Inner type (e.g., "u32", "CreateUser", "Pool")
        val fullType: String,       // Full type text (e.g., "Path<u32>")
        val category: ParamCategory
    )

    enum class ParamCategory(val displayName: String) {
        PATH("Path Parameter"),
        QUERY("Query Parameter"),
        BODY("Request Body"),
        HEADER("Headers"),
        COMPONENT("Injected Component"),
        CONFIG("Injected Config"),
        STATE("Application State"),
        STREAM("Stream"),
        OTHER("Other")
    }

    /**
     * Parsed handler info.
     */
    data class HandlerInfo(
        val params: List<HandlerParam>,
        val returnType: String?,
        val isAsync: Boolean
    )

    /** Known extractor types and their categories. */
    private val EXTRACTOR_CATEGORIES = mapOf(
        "Path" to ParamCategory.PATH,
        "Query" to ParamCategory.QUERY,
        "Json" to ParamCategory.BODY,
        "Form" to ParamCategory.BODY,
        "Multipart" to ParamCategory.BODY,
        "Bytes" to ParamCategory.BODY,
        "BodyStream" to ParamCategory.BODY,
        "Component" to ParamCategory.COMPONENT,
        "Config" to ParamCategory.CONFIG,
        "State" to ParamCategory.STATE,
        "HeaderMap" to ParamCategory.HEADER,
        "TypedHeader" to ParamCategory.HEADER,
        "StreamKey" to ParamCategory.STREAM,
    )

    /**
     * Parse a handler function's signature.
     */
    fun parseHandler(fn: RsFunction): HandlerInfo {
        val params = mutableListOf<HandlerParam>()

        // Parse each parameter.
        fn.valueParameterList?.valueParameterList?.forEach { param ->
            parseParam(param)?.let { params.add(it) }
        }

        // Parse return type.
        val returnType = fn.retType?.typeReference?.text

        // Check if async by looking for `async` keyword in the function text.
        val isAsync = fn.node.findChildByType(org.rust.lang.core.psi.RsElementTypes.ASYNC) != null

        return HandlerInfo(
            params = params,
            returnType = returnType,
            isAsync = isAsync
        )
    }

    /**
     * Parse a single function parameter.
     *
     * Handles patterns like:
     * - `Path(id): Path<u32>`
     * - `Json(body): Json<CreateUser>`
     * - `Component(db): Component<Pool>`
     * - `headers: HeaderMap`
     * - `topic: StreamKey`
     */
    private fun parseParam(param: RsValueParameter): HandlerParam? {
        val typeRef = param.typeReference ?: return null
        val fullType = typeRef.text ?: return null

        // Extract the binding name from the pattern.
        val bindingName = extractBindingName(param)

        // Determine extractor type and inner type.
        val (extractorType, innerType) = parseExtractorType(fullType)

        val category = EXTRACTOR_CATEGORIES[extractorType] ?: ParamCategory.OTHER

        return HandlerParam(
            name = bindingName,
            extractorType = extractorType,
            innerType = innerType,
            fullType = fullType,
            category = category
        )
    }

    /**
     * Extract the binding name from a parameter pattern.
     *
     * `Path(id): Path<u32>` -> "id"
     * `Json(body): Json<T>` -> "body"
     * `headers: HeaderMap` -> "headers"
     */
    private fun extractBindingName(param: RsValueParameter): String {
        val pat = param.pat
        if (pat != null) {
            val patText = pat.text
            // Pattern like `Path(id)` or `Json(body)` — extract the inner name.
            val innerPattern = Regex("""^\w+\((\w+)\)$""")
            innerPattern.find(patText)?.let { return it.groupValues[1] }
            // Simple pattern like `headers`.
            return patText
        }
        return "?"
    }

    /**
     * Parse extractor type from the full type text.
     *
     * `Path<u32>` -> ("Path", "u32")
     * `Json<CreateUser>` -> ("Json", "CreateUser")
     * `HeaderMap` -> ("HeaderMap", "HeaderMap")
     * `Component<ConnectPool>` -> ("Component", "ConnectPool")
     */
    private fun parseExtractorType(fullType: String): Pair<String, String> {
        // Generic type: Extractor<InnerType>
        val genericPattern = Regex("""^(\w+)<(.+)>$""")
        genericPattern.find(fullType.trim())?.let { match ->
            return Pair(match.groupValues[1], match.groupValues[2])
        }

        // Non-generic type: HeaderMap, StreamKey, etc.
        return Pair(fullType.trim(), fullType.trim())
    }
}
