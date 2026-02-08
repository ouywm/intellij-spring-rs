package com.springrs.plugin.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.routes.SpringRsComponentIndex
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.name

/**
 * Dependency injection validation annotator.
 *
 * Validates `#[inject]` fields in `#[derive(Service)]` structs:
 * - Checks that the injected component type is registered (another `#[derive(Service)]` exists)
 * - Reports a warning if the target component is not found
 *
 * Triggers on each FIELD identifier (not struct identifier) to avoid range issues.
 */
class SpringRsDiValidationAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (DumbService.isDumb(element.project)) return

        // Trigger on field identifiers inside a struct.
        val field = element.parent as? RsNamedFieldDecl ?: return
        if (field.identifier != element) return

        // Check if field has #[inject] attribute.
        val hasInjectAttr = field.outerAttrList.any { it.metaItem.name == "inject" }
        if (!hasInjectAttr) return

        // Check if the containing struct has #[derive(Service)].
        val struct = PsiTreeUtil.getParentOfType(field, RsStructItem::class.java) ?: return
        if (!hasServiceDerive(struct)) return

        // Get the injected type name.
        val typeRef = field.typeReference ?: return
        val targetType = extractInjectedTypeName(typeRef) ?: return

        // Skip well-known framework types.
        if (targetType in FRAMEWORK_COMPONENT_TYPES) return

        // Collect all known Service types in the project.
        val serviceTypes = collectServiceTypes(element.project)

        // Check if the target type exists as a Service.
        if (targetType !in serviceTypes) {
            holder.newAnnotation(
                HighlightSeverity.WEAK_WARNING,
                SpringRsBundle.message("springrs.di.unresolved.component", targetType)
            ).range(element.textRange).create()
        }
    }

    private fun extractInjectedTypeName(typeRef: RsTypeReference): String? {
        val pathType = typeRef as? RsPathType ?: return null
        val typeName = pathType.path.referenceName ?: return null

        if (typeName in WRAPPER_TYPES) {
            val typeArgs = pathType.path.typeArgumentList?.typeReferenceList
            if (!typeArgs.isNullOrEmpty()) {
                return extractInjectedTypeName(typeArgs.first())
            }
        }

        return typeName
    }

    private fun hasServiceDerive(struct: RsStructItem): Boolean {
        return struct.outerAttrList
            .map { it.metaItem }
            .filter { it.name == "derive" }
            .any { deriveAttr ->
                deriveAttr.metaItemArgs?.metaItemList?.any { it.name == "Service" } == true
            }
    }

    /**
     * Collects all Service type names using [SpringRsComponentIndex] cached results
     * instead of scanning the entire project on every annotate call.
     */
    private fun collectServiceTypes(project: com.intellij.openapi.project.Project): Set<String> {
        return SpringRsComponentIndex.getComponentsCached(project)
            .filter { it.type == SpringRsComponentIndex.ComponentType.SERVICE }
            .mapTo(mutableSetOf()) { it.name }
    }

    companion object {
        private val WRAPPER_TYPES = setOf(
            "Option", "Arc", "Box", "Rc",
            "LazyComponent", "ComponentRef", "ConfigRef"
        )

        private val FRAMEWORK_COMPONENT_TYPES = setOf(
            // Database
            "ConnectPool", "DatabasePool", "Pool",
            "DbConn", "DatabaseConnection",
            // Redis
            "Redis", "RedisPool", "RedisConnection",
            // Postgres
            "Postgres", "Client",
            // Mail
            "Mailer",
            // OpenDAL
            "Op", "Operator",
            // Stream
            "Producer",
            // Job
            "JobScheduler",
            // App state
            "AppState", "State",
            // Primitives
            "String", "bool", "i32", "u32", "i64", "u64", "f32", "f64", "usize", "isize"
        )
    }
}
