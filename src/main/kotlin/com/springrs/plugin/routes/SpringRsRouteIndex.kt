package com.springrs.plugin.routes

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.utils.FilePathValidator
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsMethodCall
import org.rust.lang.core.psi.rustPsiManager
import org.toml.lang.TomlLanguage

/**
 * Collects spring-rs (axum) routes from Rust source code.
 *
 * This is used by:
 * - route gutter icons (quick local parsing)
 * - tool windows / debug panels (global view)
 *
 * Supports reading prefixes from config files:
 * - global_prefix: global route prefix applied to all routes
 * - [web.openapi].doc_prefix: OpenAPI doc prefix (currently not applied to route display)
 */
object SpringRsRouteIndex {

    data class Route(
        val method: String,
        val fullPath: String,
        val handlerName: String?,
        val file: VirtualFile,
        val offset: Int,
        val kind: Kind
    ) {
        enum class Kind { ATTRIBUTE, ROUTE_CALL }
    }

    private val ROUTES_KEY: Key<CachedValue<List<Route>>> =
        Key.create("com.springrs.plugin.routes.SpringRsRouteIndex.ROUTES")

    fun getRoutesCached(project: Project): List<Route> {
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            ROUTES_KEY,
            {
                val routes = buildRoutes(project)
                // Use the route-specific modification tracker which increments on:
                // 1) any PSI change (including string literal edits inside function bodies)
                // 2) Rust structure changes (add/remove functions, change attributes, etc.)
                // Also track TOML changes (config edits).
                CachedValueProvider.Result.create(
                    routes,
                    SpringRsRouteModificationTracker.getInstance(project),
                    PsiModificationTracker.getInstance(project).forLanguage(TomlLanguage)
                )
            },
            false
        )
    }

    private fun buildRoutes(project: Project): List<Route> {
        val routes = mutableListOf<Route>()
        val scope = GlobalSearchScope.projectScope(project)

        // Workspace crate info used to resolve the owning crate for a file.
        val crateInfos = getWorkspaceCrates(project)

        // Collect global_prefix per crate.
        val cratePrefixes = mutableMapOf<String, String?>()
        for (crate in crateInfos) {
            val config = SpringRsConfigPrefixUtil.getConfigPrefixes(project, crate.rootPath)
            cratePrefixes[crate.rootPath] = config.globalPrefix
        }

        for (vFile in FileTypeIndex.getFiles(RsFileType, scope)) {
            ProgressManager.checkCanceled()

            // Skip macro-expanded files to avoid showing duplicated routes.
            if (FilePathValidator.isMacroExpanded(vFile.path)) continue

            val rsFile = PsiManager.getInstance(project).findFile(vFile) as? RsFile ?: continue

            // Resolve the owning crate and its global_prefix.
            val crateRoot = findCrateRootForFile(crateInfos, vFile.path)
            val globalPrefix = if (crateRoot != null) cratePrefixes[crateRoot] else null

            // Attribute-based routes (spring_web macros: #[get], #[post], #[route], etc.)
            for (fn in PsiTreeUtil.findChildrenOfType(rsFile, RsFunction::class.java)) {
                ProgressManager.checkCanceled()
                collectAttributeRoutes(vFile, fn, globalPrefix, routes)
            }

            // Native axum router builder routes: Router::new().route("/path", get(handler))
            for (call in PsiTreeUtil.findChildrenOfType(rsFile, RsMethodCall::class.java)) {
                ProgressManager.checkCanceled()
                collectRouteCallRoutes(vFile, call, globalPrefix, routes)
            }
        }

        return routes
            .distinctBy { "${it.method} ${it.fullPath} ${it.file.path}:${it.offset}" }
            .sortedWith(compareBy<Route> { it.fullPath }.thenBy { it.method }.thenBy { it.file.path })
    }

    private fun collectAttributeRoutes(vFile: VirtualFile, fn: RsFunction, globalPrefix: String?, out: MutableList<Route>) {
        val fnName = fn.name
        val offset = fn.identifier.textOffset

        val nestPrefix = SpringRsRouteUtil.computeNestPrefix(fn)
        val routeInfos = SpringRsRouteUtil.extractAttributeRoutes(fn)
        for (info in routeInfos) {
            val pathWithNest = SpringRsRouteUtil.joinPaths(nestPrefix, info.path)
            // Apply global_prefix.
            val fullPath = if (globalPrefix != null) {
                SpringRsRouteUtil.joinPaths(globalPrefix, pathWithNest)
            } else {
                pathWithNest
            }
            out.add(Route(info.method, fullPath, fnName, vFile, offset, Route.Kind.ATTRIBUTE))
        }
    }

    private fun collectRouteCallRoutes(vFile: VirtualFile, call: RsMethodCall, globalPrefix: String?, out: MutableList<Route>) {
        // Only handle `.route(...)` calls, not `.nest(...)`, `.merge(...)`, etc.
        if (call.referenceName != "route") return

        val offset = call.textOffset

        // Compute nest prefix in the Router call chain.
        val nestPrefix = SpringRsRouteUtil.computeRouterNestPrefix(call)

        val routeInfos = SpringRsRouteUtil.extractRouterCallRoutes(call)
        for (info in routeInfos) {
            // Apply nest prefix first.
            val pathWithNest = SpringRsRouteUtil.joinPaths(nestPrefix, info.path)
            // Then apply global_prefix.
            val fullPath = if (globalPrefix != null) {
                SpringRsRouteUtil.joinPaths(globalPrefix, pathWithNest)
            } else {
                pathWithNest
            }
            out.add(Route(info.method, fullPath, info.handlerName, vFile, offset, Route.Kind.ROUTE_CALL))
        }
    }

    /**
     * Workspace crate info.
     */
    private data class CrateInfo(val name: String, val rootPath: String)

    private fun getWorkspaceCrates(project: Project): List<CrateInfo> {
        val cargo = project.service<CargoProjectsService>()

        return cargo.allProjects
            .asSequence()
            .flatMap { it.workspace?.packages?.asSequence() ?: emptySequence() }
            .filter { it.origin == PackageOrigin.WORKSPACE }
            .mapNotNull { pkg ->
                val root = pkg.contentRoot ?: return@mapNotNull null
                CrateInfo(name = pkg.name, rootPath = root.path)
            }
            .distinctBy { it.rootPath }
            .toList()
    }

    /**
     * Finds the crate root for a file path.
     */
    private fun findCrateRootForFile(crates: List<CrateInfo>, filePath: String): String? {
        var best: CrateInfo? = null
        for (c in crates) {
            if (filePath == c.rootPath || filePath.startsWith(c.rootPath.trimEnd('/') + "/")) {
                if (best == null || c.rootPath.length > best.rootPath.length) best = c
            }
        }
        return best?.rootPath
    }
}
