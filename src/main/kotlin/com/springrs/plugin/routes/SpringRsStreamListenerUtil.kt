package com.springrs.plugin.routes

import com.intellij.openapi.diagnostic.Logger
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsLitExpr
import org.rust.lang.core.psi.RsMetaItem
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue

/**
 * Utilities for extracting spring-rs stream listener info from Rust PSI.
 *
 * Supported attribute macro forms:
 * - #[stream_listener("topic")]
 * - #[stream_listener("topic1", "topic2")]
 * - #[stream_listener("topic", consumer_mode = "RealTime")]
 * - #[stream_listener("topic", group_id = "my-group")]
 * - #[stream_listener("topic", kafka_consumer_options = fn_name)]
 * - #[stream_listener("topic", file_consumer_options = fn_name)]
 */
object SpringRsStreamListenerUtil {

    private val LOG = Logger.getInstance(SpringRsStreamListenerUtil::class.java)

    data class StreamListenerInfo(
        val topics: List<String>,
        val consumerMode: String? = null,
        val groupId: String? = null,
        val optionsFunction: String? = null,
        val optionsType: String? = null
    ) {
        val primaryTopic: String get() = topics.firstOrNull() ?: "?"
        fun topicsDisplay(): String = topics.joinToString(", ")
    }

    private val OPTION_PARAMS = mapOf(
        "kafka_consumer_options" to "kafka",
        "redis_consumer_options" to "redis",
        "file_consumer_options" to "file",
        "stdio_consumer_options" to "stdio"
    )

    fun hasStreamListenerAttribute(fn: RsFunction): Boolean {
        return fn.outerAttrList.any { it.metaItem.name == "stream_listener" }
    }

    /**
     * Extracts stream listener info from a function's attributes.
     *
     * The Rust PSI parser does not reliably expose string literals in
     * metaItemArgs.litExprList when the attribute has mixed arguments
     * (e.g. "topic", key = value). Therefore we parse from args.text directly
     * for topics, and use metaItemList for named parameters.
     */
    fun extractStreamListenerInfo(fn: RsFunction): StreamListenerInfo? {
        for (attr in fn.outerAttrList) {
            val meta = attr.metaItem
            if (meta.name != "stream_listener") continue

            val args = meta.metaItemArgs ?: continue
            val argsText = args.text ?: continue

            // === Extract topics from text ===
            // Parse all quoted strings that are NOT values of key=value pairs.
            val topics = extractTopicsFromText(argsText)

            // === Extract named parameters from PSI (more reliable for key=value) ===
            var consumerMode: String? = null
            var groupId: String? = null
            var optionsFunction: String? = null
            var optionsType: String? = null

            for (metaItem in args.metaItemList) {
                val paramName = metaItem.name ?: continue
                when (paramName) {
                    "consumer_mode" -> consumerMode = extractMetaValue(metaItem)
                    "group_id" -> groupId = extractMetaValue(metaItem)
                    in OPTION_PARAMS -> {
                        optionsType = OPTION_PARAMS[paramName]
                        optionsFunction = extractMetaValue(metaItem)
                    }
                }
            }

            // If PSI metaItemList is also empty, parse named params from text too.
            if (args.metaItemList.isEmpty()) {
                val namedParams = extractNamedParamsFromText(argsText)
                consumerMode = consumerMode ?: namedParams["consumer_mode"]
                groupId = groupId ?: namedParams["group_id"]
                for ((key, backendType) in OPTION_PARAMS) {
                    if (key in namedParams) {
                        optionsType = backendType
                        optionsFunction = namedParams[key]
                    }
                }
            }

            if (topics.isEmpty()) {
                LOG.warn("stream_listener: no topics extracted for ${fn.name}, argsText=$argsText")
                continue
            }

            return StreamListenerInfo(topics, consumerMode, groupId, optionsFunction, optionsType)
        }
        return null
    }

    /**
     * Extracts topic strings from the raw args text.
     *
     * Parses all `"..."` strings that are NOT part of a `key = "value"` pattern.
     * Input example: `("topic1", "topic2", kafka_consumer_options = fill_fn)`
     */
    private fun extractTopicsFromText(argsText: String): List<String> {
        val topics = mutableListOf<String>()
        // Remove outer parentheses.
        val inner = argsText.trim().removeSurrounding("(", ")")

        // Split by commas (respecting quotes).
        val parts = splitArgs(inner)

        for (part in parts) {
            val trimmed = part.trim()
            // Skip named parameters (contain `=`).
            if (trimmed.contains('=')) continue
            // Extract quoted string.
            val match = Regex("""^"(.*)"$""").find(trimmed)
            if (match != null) {
                topics.add(match.groupValues[1])
            }
        }
        return topics
    }

    /**
     * Extracts named parameters from the raw args text.
     * Returns map of param_name -> value_text.
     */
    private fun extractNamedParamsFromText(argsText: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val inner = argsText.trim().removeSurrounding("(", ")")
        val parts = splitArgs(inner)

        for (part in parts) {
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx <= 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim().removeSurrounding("\"")
            params[key] = value
        }
        return params
    }

    /**
     * Splits a comma-separated argument list, respecting quoted strings.
     */
    private fun splitArgs(text: String): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (ch in text) {
            when {
                ch == '"' -> { inQuotes = !inQuotes; current.append(ch) }
                ch == ',' && !inQuotes -> { parts.add(current.toString()); current = StringBuilder() }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    private fun extractMetaValue(metaItem: RsMetaItem): String? {
        metaItem.litExpr?.stringValue?.let { return it }

        val eq = metaItem.eq ?: return null
        var sibling = eq.nextSibling
        while (sibling != null) {
            val text = sibling.text.trim()
            if (text.isNotEmpty()) return text.removeSurrounding("\"")
            sibling = sibling.nextSibling
        }
        return null
    }
}
