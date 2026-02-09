package com.springrs.plugin.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.routes.SpringRsComponentIndex
import com.springrs.plugin.utils.RustTypeUtils
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.rustPsiManager

/**
 * Dependency injection validation annotator.
 *
 * Validates `#[inject]` fields in `#[derive(Service)]` structs:
 * - Checks that the injected component type is registered (another `#[derive(Service)]` exists)
 * - Reports a warning if the target component is not found
 *
 * Framework component types are resolved **dynamically** based on Cargo dependencies.
 * For example, if the project depends on `spring-sea-orm`, `DbConn` is automatically
 * recognized as a framework type. This eliminates the need for a hardcoded whitelist
 * that could become stale when spring-rs plugins update their type names.
 */
class SpringRsDiValidationAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (DumbService.isDumb(element.project)) return

        // Trigger on field identifiers inside a struct.
        val field = element.parent as? RsNamedFieldDecl ?: return
        if (field.identifier != element) return

        // Check if field has #[inject] attribute.
        val injectAttr = field.outerAttrList.find { it.metaItem.name == "inject" } ?: return
        val injectKind = extractInjectKind(injectAttr)

        // Check if the containing struct has #[derive(Service)].
        val struct = PsiTreeUtil.getParentOfType(field, RsStructItem::class.java) ?: return
        if (!hasServiceDerive(struct)) return

        // Get the injected type name.
        val typeRef = field.typeReference ?: return
        val targetType = extractInjectedTypeName(typeRef) ?: return

        // Skip Rust primitive types (bool, i32, u64, String, etc.).
        if (RustTypeUtils.isPrimitiveType(targetType) || targetType == "String") return

        val project = element.project

        when (injectKind) {
            InjectKind.CONFIG -> {
                // Config injection: validate against cached Configuration types.
                val configTypes = collectConfigTypes(project)
                if (targetType !in configTypes) {
                    holder.newAnnotation(
                        HighlightSeverity.WEAK_WARNING,
                        SpringRsBundle.message("springrs.di.unresolved.config", targetType)
                    ).range(element.textRange).create()
                }
            }
            InjectKind.COMPONENT -> {
                // Component injection: skip framework types, then validate against Service types.
                val frameworkTypes = resolveFrameworkComponentTypes(project)
                if (targetType in frameworkTypes) return

                val serviceTypes = collectServiceTypes(project)
                if (targetType !in serviceTypes) {
                    holder.newAnnotation(
                        HighlightSeverity.WEAK_WARNING,
                        SpringRsBundle.message("springrs.di.unresolved.component", targetType)
                    ).range(element.textRange).create()
                }
            }
        }
    }

    /**
     * Injection kind: `#[inject(component)]` or `#[inject(config)]`.
     * Default (bare `#[inject]`) is treated as COMPONENT.
     */
    private enum class InjectKind { COMPONENT, CONFIG }

    /**
     * Extract inject kind from `#[inject]` / `#[inject(component)]` / `#[inject(config)]`.
     */
    private fun extractInjectKind(attr: org.rust.lang.core.psi.RsOuterAttr): InjectKind {
        val meta = attr.metaItem
        val args = meta.metaItemArgs?.metaItemList
        if (args != null) {
            for (arg in args) {
                if (arg.name == "config") return InjectKind.CONFIG
            }
        }
        return InjectKind.COMPONENT
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
     * Collects all Service type names from [SpringRsComponentIndex] cache.
     */
    private fun collectServiceTypes(project: Project): Set<String> {
        return SpringRsComponentIndex.getComponentsCached(project)
            .filter { it.type == SpringRsComponentIndex.ComponentType.SERVICE }
            .mapTo(mutableSetOf()) { it.name }
    }

    /**
     * Collects all Configuration type names from [SpringRsComponentIndex] cache.
     * This includes structs with `#[derive(Configurable)]` or manual `Configurable` impl.
     */
    private fun collectConfigTypes(project: Project): Set<String> {
        return SpringRsComponentIndex.getComponentsCached(project)
            .filter { it.type == SpringRsComponentIndex.ComponentType.CONFIGURATION }
            .mapTo(mutableSetOf()) { it.name }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Dynamic framework type resolution
    // ══════════════════════════════════════════════════════════════

    companion object {
        private val FRAMEWORK_TYPES_KEY: Key<CachedValue<Set<String>>> =
            Key.create("com.springrs.plugin.annotator.SpringRsDiValidation.FRAMEWORK_TYPES")

        /**
         * Wrapper types that should be unwrapped to get the inner component type.
         * Includes standard Rust wrappers from [RustTypeUtils.WRAPPER_TYPES]
         * plus spring-rs specific component wrappers.
         */
        private val WRAPPER_TYPES = RustTypeUtils.WRAPPER_TYPES + setOf(
            "LazyComponent", "ComponentRef", "ConfigRef"
        )

        /**
         * Mapping from spring-rs crate names to the component types they register
         * via `app.add_component(...)` in their `Plugin::build()` implementation.
         *
         * Source: spring-rs repository, each plugin's `src/lib.rs`.
         * Each entry is derived by reading the actual pub type / pub struct
         * definitions and the add_component calls in the plugin source code.
         */
        private val CRATE_COMPONENT_MAP = mapOf(
            // pub type DbConn = sea_orm::DbConn; → app.add_component(conn)
            "spring-sea-orm" to listOf("DbConn"),
            // pub type Redis = redis::aio::ConnectionManager; → app.add_component(connect)
            "spring-redis" to listOf("Redis"),
            // pub type Postgres = Arc<tokio_postgres::Client>; → app.add_component(Postgres::new(client))
            "spring-postgres" to listOf("Postgres"),
            // pub type ConnectPool = sqlx::AnyPool | sqlx::PgPool; → app.add_component(connect_pool)
            "spring-sqlx" to listOf("ConnectPool"),
            // pub enum Mailer { Tokio(..), Stub(..) } → app.add_component(mailer)
            "spring-mail" to listOf("Mailer"),
            // pub type Op = Operator; (re-export of opendal::Operator) → app.add_component(connect)
            "spring-opendal" to listOf("Operator", "Op"),
            // pub struct Producer(...) → app.add_component(producer)
            "spring-stream" to listOf("Producer"),
            // pub type JobScheduler = tokio_cron_scheduler::JobScheduler; → app.add_component(sched)
            // pub struct Jobs(Vec<Job>) → app.add_component(Jobs::single(job))
            "spring-job" to listOf("JobScheduler", "Jobs"),
            // pub struct AppState { app: Arc<App> } → app.add_component(router)
            // pub type Router / Routers / RouterLayer / RouterLayers
            "spring-web" to listOf("AppState", "Router", "Routers", "RouterLayer", "RouterLayers"),
            // RoutesBuilder (tonic) → app.add_component(route_builder)
            "spring-grpc" to listOf("RoutesBuilder"),
            // pub type KeyValues = Vec<KeyValue> → app.add_component(key_values)
            "spring-opentelemetry" to listOf("KeyValues"),
            // SaTokenState → app.add_component(state)
            "spring-sa-token" to listOf("SaTokenState"),
            // pub type WorkerRegister = fn(...) → app.add_component(vec![worker_register])
            "spring-apalis" to listOf("WorkerRegister"),
        )

        /**
         * Resolves framework component types dynamically based on the project's Cargo dependencies.
         * Static method to avoid capturing Annotator instance in CachedValueProvider lambda.
         */
        fun resolveFrameworkComponentTypes(project: Project): Set<String> {
            if (DumbService.isDumb(project)) return emptySet()

            return CachedValuesManager.getManager(project).getCachedValue(
                project,
                FRAMEWORK_TYPES_KEY,
                {
                    val types = buildFrameworkTypes(project)
                    CachedValueProvider.Result.create(
                        types,
                        project.rustPsiManager.rustStructureModificationTrackerInDependencies
                    )
                },
                false
            )
        }

        private fun buildFrameworkTypes(project: Project): Set<String> {
            val types = mutableSetOf<String>()

            for (cargoProject in project.cargoProjects.allProjects) {
                val workspace = cargoProject.workspace ?: continue
                for (pkg in workspace.packages) {
                    if (pkg.origin != PackageOrigin.WORKSPACE) continue
                    for (dep in pkg.dependencies) {
                        CRATE_COMPONENT_MAP[dep.pkg.name]?.let { types.addAll(it) }
                    }
                }
            }

            return types
        }
    }

}
