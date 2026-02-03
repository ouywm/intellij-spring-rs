package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import org.rust.ide.lineMarkers.RsLineMarkerInfoUtils
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsPathType
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.RsTypeReference
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.name
import java.awt.event.MouseEvent

/**
 * Dependency injection field line marker provider.
 *
 * Shows a dependency injection gutter icon for fields annotated with #[inject(...)] or recognized
 * by special injection wrapper types.
 *
 * spring-rs injection supports:
 * - #[inject(component)] - inject component
 * - #[inject(config)] - inject config
 * - #[inject(func = ...)] - inject via factory function
 * - LazyComponent<T> - lazy component (auto-detected, no #[inject] needed)
 * - ComponentRef<T> - component reference (auto-detected, no #[inject] needed)
 * - ConfigRef<T> - config reference (auto-detected, no #[inject] needed)
 */
class SpringRsInjectLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    companion object {
        // Auto-detected wrapper types.
        private val AUTO_DETECT_TYPES = setOf("LazyComponent", "ComponentRef", "ConfigRef")
    }

    override fun getName(): String = SpringRsBundle.message("springrs.inject.marker.name")

    override fun getIcon() = SpringRsIcons.Inject

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        // Only handle field identifiers.
        val field = element.parent as? RsNamedFieldDecl ?: return null
        if (field.identifier != element) return null

        // Only apply to fields declared inside a Service struct.
        val struct = field.parent?.parent as? RsStructItem ?: return null
        if (!hasServiceDerive(struct)) return null

        // Resolve injection info (explicit #[inject] or auto-detected wrapper types).
        val injectInfo = getInjectInfo(field) ?: return null

        val tooltip = buildTooltip(field.name ?: SpringRsBundle.message("springrs.common.unknown"), injectInfo)

        // Create navigation handler.
        val navigationHandler = NavigateToBeanDefinitionHandler(field)

        return RsLineMarkerInfoUtils.create(
            element,
            element.textRange,
            SpringRsIcons.Inject,
            navigationHandler,
            GutterIconRenderer.Alignment.LEFT
        ) { tooltip }
    }

    /**
     * Injection info.
     */
    data class InjectInfo(
        val injectType: String, // component, config, func, lazy, componentRef, configRef
        val funcExpr: String? = null, // For func injection, store the function expression.
        val isAutoDetected: Boolean = false // Whether it was auto-detected.
    )

    /**
     * Navigation handler: go to Bean definition.
     */
    private class NavigateToBeanDefinitionHandler(
        private val field: RsNamedFieldDecl
    ) : GutterIconNavigationHandler<PsiElement> {

        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            // Handle func injection first.
            val funcExpr = extractFuncExprFromField(field)
            if (funcExpr != null) {
                // Navigate to function definition.
                val funcTarget = resolveFuncDefinition(funcExpr)
                if (funcTarget is com.intellij.pom.Navigatable) {
                    funcTarget.navigate(true)
                    return
                }
            }

            // Resolve field type reference.
            val typeReference = field.typeReference ?: return

            // Try to resolve the type and navigate to its definition.
            val targetElement = resolveTypeDefinition(typeReference)
            if (targetElement is com.intellij.pom.Navigatable) {
                targetElement.navigate(true)
            }
        }

        /**
         * Extract func expression from a field.
         */
        private fun extractFuncExprFromField(field: RsNamedFieldDecl): String? {
            for (attr in field.outerAttrList) {
                val meta = attr.metaItem
                if (meta.name == "inject") {
                    val args = meta.metaItemArgs?.metaItemList ?: continue
                    val firstArg = args.firstOrNull() ?: continue
                    if (firstArg.name == "func") {
                        // Extract value for func = ...
                        return firstArg.litExpr?.text
                            ?: firstArg.eq?.nextSibling?.text?.trim()
                            ?: extractFuncExpressionFromText(attr.text)
                    }
                }
            }
            return null
        }

        /**
         * Extract func expression from attribute text.
         */
        private fun extractFuncExpressionFromText(attrText: String): String? {
            val regex = Regex("""func\s*=\s*(.+?)\s*\)""")
            val match = regex.find(attrText)
            return match?.groupValues?.getOrNull(1)?.trim()
        }

        /**
         * Resolve function definition.
         *
         * Supports Self::func_name(...) and func_name(...) forms.
         */
        private fun resolveFuncDefinition(funcExpr: String): PsiElement? {
            // Extract function name.
            // Examples: Self::init_star_count(&config) or init_zero_count()
            val funcName = extractFuncName(funcExpr) ?: return null

            // Resolve the struct owning this field.
            val struct = field.parent?.parent as? RsStructItem ?: return null

            // If it starts with Self::, search in impl blocks of the struct.
            if (funcExpr.startsWith("Self::")) {
                return findFunctionInImpl(struct, funcName)
            }

            // Otherwise, search top-level functions in the same file.
            return findTopLevelFunction(struct, funcName)
        }

        /**
         * Extract function name.
         */
        private fun extractFuncName(funcExpr: String): String? {
            // Strip Self:: prefix.
            val withoutSelf = funcExpr.removePrefix("Self::")
            // Take part before '('.
            val parenIndex = withoutSelf.indexOf('(')
            return if (parenIndex > 0) {
                withoutSelf.substring(0, parenIndex).trim()
            } else {
                withoutSelf.trim()
            }
        }

        /**
         * Find a function in impl blocks of the given struct.
         */
        private fun findFunctionInImpl(struct: RsStructItem, funcName: String): RsFunction? {
            val structName = struct.name ?: return null
            val file = struct.containingFile ?: return null

            // Find all impl blocks.
            val implItems = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, RsImplItem::class.java)
            for (impl in implItems) {
                // Check whether this impl belongs to this struct.
                val implTypeName = impl.typeReference?.text
                if (implTypeName == structName) {
                    // Find functions inside the impl block.
                    val functions = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(impl, RsFunction::class.java)
                    for (func in functions) {
                        if (func.name == funcName) {
                            return func
                        }
                    }
                }
            }
            return null
        }

        /**
         * Find a top-level function in the current file.
         */
        private fun findTopLevelFunction(struct: RsStructItem, funcName: String): RsFunction? {
            val file = struct.containingFile ?: return null

            // Find all functions in the file.
            val functions = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, RsFunction::class.java)
            for (func in functions) {
                // Only consider top-level functions (not inside impl blocks).
                if (func.parent?.parent !is RsImplItem && func.name == funcName) {
                    return func
                }
            }
            return null
        }

        /**
         * Resolve type definition.
         */
        private fun resolveTypeDefinition(typeRef: RsTypeReference): RsElement? {
            val pathType = typeRef as? RsPathType ?: return null
            val path = pathType.path

            // Resolve type name.
            val typeName = path.referenceName ?: return null

            // If wrapper type, resolve inner type.
            if (typeName in listOf("Option", "LazyComponent", "ComponentRef", "ConfigRef")) {
                val typeArgs = path.typeArgumentList?.typeReferenceList
                if (!typeArgs.isNullOrEmpty()) {
                    // Recurse into inner type.
                    return resolveTypeDefinition(typeArgs.first())
                }
            }

            // Resolve type definition.
            return path.reference?.resolve() as? RsElement
        }
    }

    /**
     * Check whether a struct has #[derive(Service)].
     */
    private fun hasServiceDerive(struct: RsStructItem): Boolean {
        for (attr in struct.outerAttrList) {
            val meta = attr.metaItem
            val attrName = meta.name ?: continue

            if (attrName == "derive") {
                val args = meta.metaItemArgs?.metaItemList ?: continue
                for (arg in args) {
                    if (arg.name == "Service") {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Resolve injection info for a field.
     *
     * Supports explicit #[inject] attribute and auto-detected wrapper types.
     */
    private fun getInjectInfo(field: RsNamedFieldDecl): InjectInfo? {
        // 1) Check explicit #[inject(...)] attribute first.
        for (attr in field.outerAttrList) {
            val meta = attr.metaItem
            if (meta.name == "inject") {
                val args = meta.metaItemArgs?.metaItemList
                if (args.isNullOrEmpty()) {
                    // #[inject] without args defaults to "component".
                    return InjectInfo("component")
                }

                val firstArg = args.firstOrNull()
                val argName = firstArg?.name

                return when (argName) {
                    "component" -> InjectInfo("component")
                    "config" -> InjectInfo("config")
                    "func" -> {
                        // Extract value for func = ...
                        val funcExpr = firstArg.litExpr?.text
                            ?: firstArg.metaItemArgs?.text
                            ?: firstArg.eq?.nextSibling?.text?.trim()
                            ?: extractFuncExpression(attr.text)
                        InjectInfo("func", funcExpr)
                    }
                    else -> InjectInfo(argName ?: "component")
                }
            }
        }

        // 2) If there is no #[inject], try auto-detect wrapper types.
        val typeRef = field.typeReference ?: return null
        val autoDetectedType = detectAutoInjectType(typeRef)
        if (autoDetectedType != null) {
            return InjectInfo(autoDetectedType, isAutoDetected = true)
        }

        return null
    }

    /**
     * Detect auto-inject type.
     *
     * Supports LazyComponent<T>, ComponentRef<T>, ConfigRef<T>.
     */
    private fun detectAutoInjectType(typeRef: RsTypeReference): String? {
        val pathType = typeRef as? RsPathType ?: return null
        val typeName = pathType.path.referenceName ?: return null

        // Check auto-detect wrapper types.
        return when (typeName) {
            "LazyComponent" -> "lazy"
            "ComponentRef" -> "componentRef"
            "ConfigRef" -> "configRef"
            "Option" -> {
                // Check whether Option wraps an auto-detected type.
                val typeArgs = pathType.path.typeArgumentList?.typeReferenceList
                if (!typeArgs.isNullOrEmpty()) {
                    val innerTypeRef = typeArgs.first()
                    val innerPathType = innerTypeRef as? RsPathType
                    val innerTypeName = innerPathType?.path?.referenceName
                    when (innerTypeName) {
                        "LazyComponent" -> "lazy"
                        "ComponentRef" -> "componentRef"
                        "ConfigRef" -> "configRef"
                        else -> null
                    }
                } else null
            }
            else -> null
        }
    }

    /**
     * Extract func expression from attribute text.
     */
    private fun extractFuncExpression(attrText: String): String? {
        // Match #[inject(func = ...)] pattern.
        val regex = Regex("""func\s*=\s*(.+?)\s*\)""")
        val match = regex.find(attrText)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    /**
     * Build tooltip.
     */
    private fun buildTooltip(fieldName: String, info: InjectInfo): String {
        val sb = StringBuilder()
        sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.inject", fieldName))
        sb.append("\n")

        when (info.injectType) {
            "component" -> sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.type.component"))
            "config" -> sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.type.config"))
            "func" -> {
                sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.type.func"))
                if (info.funcExpr != null) {
                    sb.append("\n${SpringRsBundle.message("springrs.inject.marker.tooltip.function", info.funcExpr)}")
                }
            }
            "lazy" -> sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.type.lazy"))
            "componentRef" -> sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.type.componentRef"))
            "configRef" -> sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.type.configRef"))
            else -> sb.append(SpringRsBundle.message("springrs.inject.marker.tooltip.type.other", info.injectType))
        }

        if (info.isAutoDetected) {
            sb.append("\n${SpringRsBundle.message("springrs.inject.marker.tooltip.auto.detected")}")
        }

        sb.append("\n\n${SpringRsBundle.message("springrs.inject.marker.tooltip.click.navigate")}")

        return sb.toString()
    }
}
