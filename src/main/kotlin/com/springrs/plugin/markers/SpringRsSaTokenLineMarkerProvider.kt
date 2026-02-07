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
 * Gutter icons for spring-rs sa-token authentication macros.
 *
 * Shows a lock/shield icon on functions annotated with:
 * - #[sa_check_login]
 * - #[sa_check_role("admin")]
 * - #[sa_check_permission("user:delete")]
 * - #[sa_check_roles_and("admin", "super")]
 * - #[sa_check_roles_or("admin", "manager")]
 * - #[sa_check_permissions_and("user:read", "user:write")]
 * - #[sa_check_permissions_or("admin:*", "user:delete")]
 * - #[sa_ignore]
 */
class SpringRsSaTokenLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    companion object {
        /** All sa-token attribute macro names. */
        private val SA_TOKEN_ATTRS = mapOf(
            "sa_check_login" to "Login Required",
            "sa_check_role" to "Role Check",
            "sa_check_permission" to "Permission Check",
            "sa_check_roles_and" to "Roles (AND)",
            "sa_check_roles_or" to "Roles (OR)",
            "sa_check_permissions_and" to "Permissions (AND)",
            "sa_check_permissions_or" to "Permissions (OR)",
            "sa_ignore" to "Auth Ignored"
        )
    }

    override fun getName(): String = SpringRsBundle.message("springrs.satoken.marker.name")

    override fun getIcon() = SpringRsIcons.SecurityRole

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        val fn = element.parent as? RsFunction ?: return null
        if (fn.identifier != element) return null

        val authInfo = extractSaTokenInfo(fn) ?: return null

        val fnName = fn.name ?: "unknown"
        val tooltip = buildTooltip(fnName, authInfo)

        // Skip #[sa_ignore] — a warning icon would be misleading.
        if (authInfo.attrName == "sa_ignore") return null

        val icon = SpringRsIcons.SecurityRole

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { _: PsiElement -> tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.satoken.marker.name") }
        )
    }

    data class SaTokenInfo(
        val attrName: String,
        val displayType: String,
        val values: List<String>
    )

    private fun extractSaTokenInfo(fn: RsFunction): SaTokenInfo? {
        for (attr in fn.outerAttrList) {
            val meta = attr.metaItem
            val attrName = meta.name ?: continue

            val displayType = SA_TOKEN_ATTRS[attrName] ?: continue

            // Extract parameter values (role names, permission strings).
            val values = mutableListOf<String>()
            val args = meta.metaItemArgs
            if (args != null) {
                val argsText = args.text ?: ""
                val inner = argsText.trim().removeSurrounding("(", ")")
                Regex(""""([^"]+)"""").findAll(inner).forEach { match ->
                    values.add(match.groupValues[1])
                }
            }

            return SaTokenInfo(attrName, displayType, values)
        }
        return null
    }

    private fun buildTooltip(fnName: String, info: SaTokenInfo): String {
        val sb = StringBuilder()
        sb.append("sa-token: ${info.displayType}")
        sb.append("\nHandler: $fnName")
        if (info.values.isNotEmpty()) {
            sb.append("\nValues: ${info.values.joinToString(", ")}")
        }
        return sb.toString()
    }
}
