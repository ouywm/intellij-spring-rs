package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import com.springrs.plugin.utils.CargoUtils
import org.rust.ide.lineMarkers.RsLineMarkerInfoUtils
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsPathType
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.RsTypeReference
import org.rust.lang.core.psi.ext.name
import java.awt.event.MouseEvent
import javax.swing.JList

/**
 * Service line marker provider.
 *
 * Shows a Spring Bean icon for structs annotated with #[derive(Service)].
 * Clicking it shows where this service is injected (who uses it).
 */
class SpringRsServiceLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.service.marker.name")

    override fun getIcon() = SpringRsIcons.SpringRsBean

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        // Only handle struct identifier.
        val struct = element.parent as? RsStructItem ?: return null
        if (struct.identifier != element) return null

        // Check for #[derive(Service)].
        if (!hasServiceDerive(struct)) return null

        val serviceName = struct.name ?: return null

        // Collect service info.
        val serviceInfo = getServiceInfo(struct)
        val tooltip = buildTooltip(serviceName, serviceInfo)

        // Navigation handler: find injection usages for this service.
        val navigationHandler = ShowInjectionUsagesHandler(serviceName, struct)

        return RsLineMarkerInfoUtils.create(
            element,
            element.textRange,
            SpringRsIcons.SpringRsBean,
            navigationHandler,
            GutterIconRenderer.Alignment.LEFT
        ) { tooltip }
    }

    /**
     * Injection usage info.
     */
    data class InjectionUsageInfo(
        val field: RsNamedFieldDecl,
        val fieldName: String,
        val structName: String,
        val injectType: String
    )

    /**
     * Navigation handler that shows injection usages.
     */
    private class ShowInjectionUsagesHandler(
        private val serviceName: String,
        private val serviceStruct: RsStructItem
    ) : GutterIconNavigationHandler<PsiElement> {

        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            if (e == null) return

            // Find all injection usages.
            val usages = findInjectionUsages()

            if (usages.isEmpty()) {
                // No usages found; show message.
                JBPopupFactory.getInstance()
                    .createMessage(SpringRsBundle.message("springrs.service.marker.no.usages", serviceName))
                    .show(RelativePoint(e))
                return
            }

            if (usages.size == 1) {
                // Single usage: navigate directly.
                usages.first().field.navigate(true)
                return
            }

            // Multiple usages: show chooser.
            val popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(usages)
                .setTitle(SpringRsBundle.message("springrs.service.marker.popup.title", serviceName))
                .setRenderer(InjectionUsageRenderer())
                .setItemChosenCallback { usage ->
                    usage.field.navigate(true)
                }
                .createPopup()

            popup.show(RelativePoint(e))
        }

        /**
         * Find all injection usages (scoped to the current crate).
         */
        private fun findInjectionUsages(): List<InjectionUsageInfo> {
            val result = mutableListOf<InjectionUsageInfo>()
            val project = serviceStruct.project
            val psiManager = com.intellij.psi.PsiManager.getInstance(project)
            val unknown = SpringRsBundle.message("springrs.common.unknown")

            // Resolve crate root for the current service.
            val serviceFilePath = serviceStruct.containingFile?.virtualFile?.path
            val crateRoot = if (serviceFilePath != null) {
                CargoUtils.findCrateRootForFile(project, serviceFilePath)
            } else null

            // Search all Rust files.
            val scope = GlobalSearchScope.projectScope(project)
            val rustFiles = FileTypeIndex.getFiles(RsFileType, scope)

            for (virtualFile in rustFiles) {
                // If crate root is known, search only files under that crate.
                if (crateRoot != null && !virtualFile.path.startsWith(crateRoot.trimEnd('/') + "/")) {
                    continue
                }

                val psiFile = psiManager.findFile(virtualFile) as? RsFile ?: continue

                // Find all structs in the file.
                val structs = PsiTreeUtil.findChildrenOfType(psiFile, RsStructItem::class.java)
                for (struct in structs) {
                    if (hasServiceDerive(struct)) {
                        // Check whether any field injects the target service.
                        val blockFields = struct.blockFields ?: continue
                        for (field in blockFields.namedFieldDeclList) {
                            if (isFieldInjectingService(field, serviceName)) {
                                val injectType = getInjectType(field)
                                result.add(InjectionUsageInfo(
                                    field = field,
                                    fieldName = field.name ?: unknown,
                                    structName = struct.name ?: unknown,
                                    injectType = injectType
                                ))
                            }
                        }
                    }
                }
            }

            return result
        }

        /**
         * Check whether a field injects the target service.
         */
        private fun isFieldInjectingService(field: RsNamedFieldDecl, targetServiceName: String): Boolean {
            val typeRef = field.typeReference ?: return false
            val typeName = extractTypeName(typeRef)
            return typeName == targetServiceName
        }

        /**
         * Extract type name (strip wrapper types).
         */
        private fun extractTypeName(typeRef: RsTypeReference): String? {
            val pathType = typeRef as? RsPathType ?: return null
            val typeName = pathType.path.referenceName ?: return null

            // If wrapper type, recurse into inner type.
            if (typeName in listOf("Option", "LazyComponent", "ComponentRef", "ConfigRef", "Arc", "Box")) {
                val typeArgs = pathType.path.typeArgumentList?.typeReferenceList
                if (!typeArgs.isNullOrEmpty()) {
                    return extractTypeName(typeArgs.first())
                }
            }

            return typeName
        }

        /**
         * Resolve inject type.
         */
        private fun getInjectType(field: RsNamedFieldDecl): String {
            // Check explicit #[inject] attribute.
            for (attr in field.outerAttrList) {
                val meta = attr.metaItem
                if (meta.name == "inject") {
                    val args = meta.metaItemArgs?.metaItemList
                    return args?.firstOrNull()?.name ?: "component"
                }
            }

            // Fallback: auto-detect injection type.
            val typeRef = field.typeReference ?: return "auto"
            val pathType = typeRef as? RsPathType ?: return "auto"
            val typeName = pathType.path.referenceName ?: return "auto"

            return when (typeName) {
                "LazyComponent" -> "lazy"
                "ComponentRef" -> "componentRef"
                "ConfigRef" -> "configRef"
                else -> "component"
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
    }

    /**
     * Renderer for injection usage list.
     */
    private class InjectionUsageRenderer : ColoredListCellRenderer<InjectionUsageInfo>() {
        override fun customizeCellRenderer(
            list: JList<out InjectionUsageInfo>,
            value: InjectionUsageInfo?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return

            icon = SpringRsIcons.Inject

            // Field name (bold).
            append(value.fieldName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

            // Owning struct (grayed).
            append(" (${value.structName})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    /**
     * Service info.
     */
    private data class ServiceInfo(
        val isPrototype: Boolean = false,
        val isGrpc: Boolean = false,
        val injectedFields: List<InjectedField> = emptyList()
    )

    /**
     * Injected field info (for tooltip).
     */
    private data class InjectedField(
        val name: String,
        val injectType: String,
        val isAutoDetected: Boolean = false
    )

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
     * Detect auto-inject type.
     */
    private fun detectAutoInjectType(typeRef: RsTypeReference): String? {
        val pathType = typeRef as? RsPathType ?: return null
        val typeName = pathType.path.referenceName ?: return null

        return when (typeName) {
            "LazyComponent" -> "lazy"
            "ComponentRef" -> "componentRef"
            "ConfigRef" -> "configRef"
            "Option" -> {
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
     * Collect service info.
     */
    private fun getServiceInfo(struct: RsStructItem): ServiceInfo {
        var isPrototype = false
        var isGrpc = false

        // Check #[service(...)] attribute.
        for (attr in struct.outerAttrList) {
            val meta = attr.metaItem
            if (meta.name == "service") {
                val args = meta.metaItemArgs?.metaItemList ?: continue
                for (arg in args) {
                    when (arg.name) {
                        "prototype" -> isPrototype = true
                        "grpc" -> isGrpc = true
                    }
                }
            }
        }

        // Collect injected fields.
        val injectedFields = mutableListOf<InjectedField>()
        val blockFields = struct.blockFields
        if (blockFields != null) {
            for (field in blockFields.namedFieldDeclList) {
                val fieldName = field.name ?: continue

                // Check explicit #[inject] attribute.
                var found = false
                for (fieldAttr in field.outerAttrList) {
                    val fieldMeta = fieldAttr.metaItem
                    if (fieldMeta.name == "inject") {
                        val injectArgs = fieldMeta.metaItemArgs?.metaItemList
                        val injectType = injectArgs?.firstOrNull()?.name ?: "component"
                        injectedFields.add(InjectedField(fieldName, injectType, false))
                        found = true
                        break
                    }
                }

                // If not explicit, try auto-detect type.
                if (!found) {
                    val typeRef = field.typeReference
                    if (typeRef != null) {
                        val autoType = detectAutoInjectType(typeRef)
                        if (autoType != null) {
                            injectedFields.add(InjectedField(fieldName, autoType, true))
                        }
                    }
                }
            }
        }

        return ServiceInfo(isPrototype, isGrpc, injectedFields)
    }

    /**
     * Build tooltip.
     */
    private fun buildTooltip(serviceName: String, info: ServiceInfo): String {
        val sb = StringBuilder()
        sb.append(SpringRsBundle.message("springrs.service.marker.tooltip.service", serviceName))

        if (info.isPrototype) {
            sb.append("\n${SpringRsBundle.message("springrs.service.marker.tooltip.prototype")}")
        }
        if (info.isGrpc) {
            sb.append("\n${SpringRsBundle.message("springrs.service.marker.tooltip.grpc")}")
        }

        if (info.injectedFields.isNotEmpty()) {
            sb.append("\n\n${SpringRsBundle.message("springrs.service.marker.tooltip.injected.fields")}")
            for (field in info.injectedFields) {
                val autoTag = if (field.isAutoDetected) " ${SpringRsBundle.message("springrs.service.marker.tooltip.field.auto")}" else ""
                sb.append("\n  • ${field.name} [${field.injectType}]$autoTag")
            }
        }

        sb.append("\n\n")
        sb.append(SpringRsBundle.message("springrs.service.marker.tooltip.click.find.usages"))

        return sb.toString()
    }
}
