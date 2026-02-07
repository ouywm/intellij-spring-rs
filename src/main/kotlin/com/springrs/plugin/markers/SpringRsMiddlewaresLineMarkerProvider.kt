package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import org.rust.lang.core.psi.RsModItem
import org.rust.lang.core.psi.ext.name

/**
 * Gutter icons for spring-rs `#[middlewares(...)]` macro on modules.
 *
 * Shows a middleware icon on modules annotated with:
 * - #[middlewares(middleware1, middleware2, ...)]
 */
class SpringRsMiddlewaresLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.middlewares.marker.name")
    override fun getIcon() = SpringRsIcons.SpringLeaf

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        // Trigger on module name identifier.
        val mod = element.parent as? RsModItem ?: return null
        if (mod.identifier != element) return null

        // Check for #[middlewares(...)] attribute.
        var middlewareNames: List<String>? = null

        for (attr in mod.outerAttrList) {
            val meta = attr.metaItem
            if (meta.name != "middlewares") continue

            val args = meta.metaItemArgs ?: continue
            val argsText = args.text?.trim()?.removeSurrounding("(", ")") ?: ""
            middlewareNames = argsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            break
        }

        if (middlewareNames == null) return null

        val modName = mod.name ?: "unknown"
        val tooltip = buildString {
            append("spring-rs Middlewares")
            append("\nModule: $modName")
            append("\nMiddlewares: ${middlewareNames.joinToString(", ")}")
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            SpringRsIcons.SpringLeaf,
            { _: PsiElement -> tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.middlewares.marker.name") }
        )
    }
}
