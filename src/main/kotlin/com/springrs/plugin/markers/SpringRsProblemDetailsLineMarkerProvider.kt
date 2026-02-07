package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import org.rust.lang.core.psi.RsEnumItem
import org.rust.lang.core.psi.ext.name

/**
 * Gutter icons for spring-rs `#[derive(ProblemDetails)]` macro on enums.
 *
 * Shows an error/exception icon on enums annotated with:
 * - #[derive(ProblemDetails)]
 *
 * ProblemDetails derives HttpStatusCode and ToProblemDetails traits
 * for RFC 7807 Problem Details responses.
 */
class SpringRsProblemDetailsLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.problemdetails.marker.name")
    override fun getIcon() = AllIcons.Nodes.ErrorIntroduction

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        val enum = element.parent as? RsEnumItem ?: return null
        if (enum.identifier != element) return null

        // Check for #[derive(ProblemDetails)].
        val hasProblemDetails = enum.outerAttrList
            .map { it.metaItem }
            .filter { it.name == "derive" }
            .any { deriveAttr ->
                deriveAttr.metaItemArgs?.metaItemList?.any { it.name == "ProblemDetails" } == true
            }

        if (!hasProblemDetails) return null

        // Collect variant info for tooltip.
        val variants = enum.enumBody?.enumVariantList ?: emptyList()
        val variantInfos = variants.mapNotNull { variant ->
            val variantName = variant.name ?: return@mapNotNull null
            val statusCode = variant.outerAttrList
                .firstOrNull { it.metaItem.name == "status_code" }
                ?.metaItem?.metaItemArgs?.let { args ->
                    val text = args.text?.trim()?.removeSurrounding("(", ")")
                    text?.trim()
                }
            val title = variant.outerAttrList
                .firstOrNull { it.metaItem.name == "title" }
                ?.metaItem?.metaItemArgs?.let { args ->
                    val text = args.text?.trim()?.removeSurrounding("(", ")")
                    text?.trim()?.removeSurrounding("\"")
                }
            "$variantName → ${statusCode ?: "?"}${if (title != null) " ($title)" else ""}"
        }

        val enumName = enum.name ?: "unknown"
        val tooltip = buildString {
            append("spring-rs ProblemDetails: $enumName")
            append("\nRFC 7807 Problem Details response")
            if (variantInfos.isNotEmpty()) {
                append("\n\nVariants:")
                variantInfos.forEach { append("\n  • $it") }
            }
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Nodes.ErrorIntroduction,
            { _: PsiElement -> tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.problemdetails.marker.name") }
        )
    }
}
