package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue

/**
 * Gutter icons for spring-rs `#[cache]` macro.
 *
 * Shows a cache icon on functions annotated with:
 * - #[cache("key_pattern")]
 * - #[cache("key_pattern", expire = 600)]
 * - #[cache("key_pattern", expire = 600, condition = ..., unless = ...)]
 *
 * Tooltip shows cache key, expire, condition, unless details.
 */
class SpringRsCacheLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.cache.marker.name")

    override fun getIcon() = SpringRsIcons.Cacheable

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        val fn = element.parent as? RsFunction ?: return null
        if (fn.identifier != element) return null

        val cacheInfo = extractCacheInfo(fn) ?: return null

        val fnName = fn.name ?: "unknown"
        val tooltip = buildTooltip(fnName, cacheInfo)

        return LineMarkerInfo(
            element,
            element.textRange,
            SpringRsIcons.Cacheable,
            { _: PsiElement -> tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.cache.marker.name") }
        )
    }

    data class CacheInfo(
        val keyPattern: String,
        val expire: String? = null,
        val condition: String? = null,
        val unless: String? = null
    )

    private fun extractCacheInfo(fn: RsFunction): CacheInfo? {
        for (attr in fn.outerAttrList) {
            val meta = attr.metaItem
            if (meta.name != "cache") continue

            val args = meta.metaItemArgs ?: continue
            val argsText = args.text ?: continue

            // Parse key pattern from first string literal.
            val keyPattern = extractKeyPattern(argsText) ?: continue

            // Parse named parameters from text.
            val expire = extractNamedParam(argsText, "expire")
            val condition = extractNamedParam(argsText, "condition")
            val unless = extractNamedParam(argsText, "unless")

            return CacheInfo(keyPattern, expire, condition, unless)
        }
        return null
    }

    private fun extractKeyPattern(argsText: String): String? {
        val inner = argsText.trim().removeSurrounding("(", ")")
        val match = Regex("""^"([^"]*)".*""").find(inner.trim())
        return match?.groupValues?.get(1)
    }

    private fun extractNamedParam(argsText: String, name: String): String? {
        val pattern = Regex("""$name\s*=\s*(.+?)(?:,\s*\w+\s*=|\)$)""")
        val match = pattern.find(argsText)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun buildTooltip(fnName: String, info: CacheInfo): String {
        val sb = StringBuilder()
        sb.append("spring-rs Cache: $fnName")
        sb.append("\nKey: ${info.keyPattern}")
        info.expire?.let { sb.append("\nExpire: ${it}s") }
        info.condition?.let { sb.append("\nCondition: $it") }
        info.unless?.let { sb.append("\nUnless: $it") }
        return sb.toString()
    }
}
