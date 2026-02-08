package com.springrs.plugin.routes

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
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
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.FilePathValidator
import com.springrs.plugin.utils.RustAttributeUtils
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlTable

/**
 * Unified scanner that collects ALL spring-rs items (routes, jobs, stream listeners, components)
 * in a single pass through the project's Rust files.
 *
 * This eliminates 4x redundant full-project scans. Instead of each Index scanning all files
 * independently, all data is collected in one traversal and cached together.
 *
 * Performance characteristics:
 * - One `FileTypeIndex.getFiles(RsFileType)` call instead of four
 * - One PSI parse per file instead of four
 * - Only scans crates that have spring-rs dependencies
 * - Cache invalidated by `PsiModificationTracker` (Rust language changes only)
 */
object SpringRsUnifiedScanner {

    /**
     * Result of a unified scan — contains all item types.
     */
    data class ScanResult(
        val routes: List<SpringRsRouteIndex.Route>,
        val jobs: List<SpringRsJobIndex.Job>,
        val streamListeners: List<SpringRsStreamListenerIndex.StreamListener>,
        val components: List<SpringRsComponentIndex.ComponentInfo>
    )

    private val SCAN_RESULT_KEY: Key<CachedValue<ScanResult>> =
        Key.create("com.springrs.plugin.routes.SpringRsUnifiedScanner.SCAN_RESULT")

    /**
     * Returns the cached unified scan result. If the cache is stale, triggers a single
     * full-project scan that collects all item types simultaneously.
     *
     * Returns an empty result during dumb mode (indexing).
     */
    fun getScanResultCached(project: Project): ScanResult {
        if (DumbService.isDumb(project)) return EMPTY_RESULT
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            SCAN_RESULT_KEY,
            {
                val result = performUnifiedScan(project)
                // Use language-specific trackers: only invalidate when Rust or TOML files change.
                // Editing Markdown, JSON, or other files won't trigger a re-scan.
                val tracker = PsiModificationTracker.getInstance(project)
                CachedValueProvider.Result.create(
                    result,
                    tracker.forLanguage(RsFileType.language),
                    tracker.forLanguage(org.toml.lang.TomlLanguage)
                )
            },
            false
        )
    }

    private val EMPTY_RESULT = ScanResult(emptyList(), emptyList(), emptyList(), emptyList())

    // ══════════════════════════════════════════════════════════════
    // ── Unified scan implementation
    // ══════════════════════════════════════════════════════════════

    private fun performUnifiedScan(project: Project): ScanResult {
        val routes = mutableListOf<SpringRsRouteIndex.Route>()
        val jobs = mutableListOf<SpringRsJobIndex.Job>()
        val listeners = mutableListOf<SpringRsStreamListenerIndex.StreamListener>()
        val components = mutableListOf<SpringRsComponentIndex.ComponentInfo>()

        val scope = GlobalSearchScope.projectScope(project)

        // ── Crate info and spring-rs filtering ──
        val crateInfos = getWorkspaceCrates(project)
        val springCrateRoots = getSpringCrateRoots(project, crateInfos)

        // Collect global_prefix per spring-rs crate (for route prefix resolution).
        val cratePrefixes = mutableMapOf<String, String?>()
        for (root in springCrateRoots) {
            val config = SpringRsConfigPrefixUtil.getConfigPrefixes(project, root)
            cratePrefixes[root] = config.globalPrefix
        }

        // Collect TOML config values (for Configuration component entries).
        val tomlConfigValues = collectTomlConfigValues(project)

        val psiManager = PsiManager.getInstance(project)

        // ── Single pass through all Rust files ──
        for (vFile in FileTypeIndex.getFiles(RsFileType, scope)) {
            ProgressManager.checkCanceled()

            // Skip macro-expanded files.
            if (FilePathValidator.isMacroExpanded(vFile.path)) continue

            // Only scan files belonging to spring-rs crates.
            val crateRoot = findCrateRootForFile(crateInfos, vFile.path)
            if (crateRoot != null && crateRoot !in springCrateRoots) continue

            val rsFile = psiManager.findFile(vFile) as? RsFile ?: continue

            val globalPrefix = if (crateRoot != null) cratePrefixes[crateRoot] else null

            // ── Scan functions: routes, jobs, stream listeners ──
            for (fn in PsiTreeUtil.findChildrenOfType(rsFile, RsFunction::class.java)) {
                ProgressManager.checkCanceled()

                // Routes (attribute-based: #[get], #[post], #[route], etc.)
                collectAttributeRoutes(vFile, fn, globalPrefix, routes)

                // Jobs (#[cron], #[fix_delay], #[fix_rate], #[one_shot])
                collectJob(vFile, fn, jobs)

                // Stream listeners (#[stream_listener])
                collectStreamListener(vFile, fn, listeners)
            }

            // ── Scan structs: Service, Configuration ──
            for (struct in PsiTreeUtil.findChildrenOfType(rsFile, RsStructItem::class.java)) {
                ProgressManager.checkCanceled()
                collectServiceComponent(vFile, struct, components)
                collectConfigurationComponent(vFile, struct, tomlConfigValues, components)
            }

            // ── Scan method calls: .route(), .add_plugin() ──
            for (call in PsiTreeUtil.findChildrenOfType(rsFile, RsMethodCall::class.java)) {
                ProgressManager.checkCanceled()
                collectRouteCallRoutes(vFile, call, globalPrefix, routes)
                collectPluginComponent(vFile, call, components)
            }
        }

        return ScanResult(
            routes = routes
                .distinctBy { "${it.method} ${it.fullPath} ${it.file.path}:${it.offset}" }
                .sortedWith(compareBy<SpringRsRouteIndex.Route> { it.fullPath }.thenBy { it.method }.thenBy { it.file.path }),
            jobs = jobs
                .distinctBy { "${it.type} ${it.handlerName} ${it.file.path}:${it.offset}" }
                .sortedWith(compareBy<SpringRsJobIndex.Job> { it.type.ordinal }.thenBy { it.handlerName ?: "" }.thenBy { it.file.path }),
            streamListeners = listeners
                .distinctBy { "${it.topics} ${it.handlerName} ${it.file.path}:${it.offset}" }
                .sortedWith(compareBy<SpringRsStreamListenerIndex.StreamListener> { it.primaryTopic }.thenBy { it.handlerName ?: "" }),
            components = components
                .distinctBy { "${it.type}:${it.name}:${it.file.path}:${it.offset}" }
                .sortedWith(compareBy<SpringRsComponentIndex.ComponentInfo> { it.type.ordinal }.thenBy { it.name })
        )
    }

    // ══════════════════════════════════════════════════════════════
    // ── Collectors (delegate to existing logic in each Index)
    // ══════════════════════════════════════════════════════════════

    // ── Routes ──

    private fun collectAttributeRoutes(
        vFile: VirtualFile, fn: RsFunction, globalPrefix: String?,
        out: MutableList<SpringRsRouteIndex.Route>
    ) {
        val fnName = fn.name
        val offset = fn.identifier?.textOffset ?: fn.textOffset

        val nestPrefix = SpringRsRouteUtil.computeNestPrefix(fn)
        val routeInfos = SpringRsRouteUtil.extractAttributeRoutes(fn)
        for (info in routeInfos) {
            val pathWithNest = SpringRsRouteUtil.joinPaths(nestPrefix, info.path)
            val fullPath = if (globalPrefix != null)
                SpringRsRouteUtil.joinPaths(globalPrefix, pathWithNest)
            else pathWithNest

            out.add(SpringRsRouteIndex.Route(info.method, fullPath, fnName, vFile, offset, SpringRsRouteIndex.Route.Kind.ATTRIBUTE))
        }
    }

    private fun collectRouteCallRoutes(
        vFile: VirtualFile, call: RsMethodCall, globalPrefix: String?,
        out: MutableList<SpringRsRouteIndex.Route>
    ) {
        if (call.referenceName != "route") return
        val offset = call.textOffset
        val nestPrefix = SpringRsRouteUtil.computeRouterNestPrefix(call)
        val routeInfos = SpringRsRouteUtil.extractRouterCallRoutes(call)

        for (info in routeInfos) {
            val pathWithNest = SpringRsRouteUtil.joinPaths(nestPrefix, info.path)
            val fullPath = if (globalPrefix != null)
                SpringRsRouteUtil.joinPaths(globalPrefix, pathWithNest)
            else pathWithNest

            out.add(SpringRsRouteIndex.Route(info.method, fullPath, info.handlerName, vFile, offset, SpringRsRouteIndex.Route.Kind.ROUTE_CALL))
        }
    }

    // ── Jobs ──

    private fun collectJob(vFile: VirtualFile, fn: RsFunction, out: MutableList<SpringRsJobIndex.Job>) {
        val jobInfo = SpringRsJobUtil.extractJobInfo(fn) ?: return
        val fnName = fn.name
        val offset = fn.identifier?.textOffset ?: fn.textOffset

        out.add(SpringRsJobIndex.Job(
            type = jobInfo.type,
            expression = jobInfo.expression,
            handlerName = fnName,
            file = vFile,
            offset = offset
        ))
    }

    // ── Stream Listeners ──

    private fun collectStreamListener(
        vFile: VirtualFile, fn: RsFunction,
        out: MutableList<SpringRsStreamListenerIndex.StreamListener>
    ) {
        val info = SpringRsStreamListenerUtil.extractStreamListenerInfo(fn) ?: return
        val fnName = fn.name
        val offset = fn.identifier?.textOffset ?: fn.textOffset

        out.add(SpringRsStreamListenerIndex.StreamListener(
            topics = info.topics,
            handlerName = fnName,
            file = vFile,
            offset = offset,
            consumerMode = info.consumerMode,
            groupId = info.groupId,
            optionsType = info.optionsType
        ))
    }

    // ── Components: Service ──

    private fun collectServiceComponent(
        vFile: VirtualFile, struct: RsStructItem,
        out: MutableList<SpringRsComponentIndex.ComponentInfo>
    ) {
        if (!hasServiceDerive(struct)) return
        val name = struct.name ?: return
        val offset = struct.identifier?.textOffset ?: struct.textOffset

        out.add(SpringRsComponentIndex.ComponentInfo(
            type = SpringRsComponentIndex.ComponentType.SERVICE,
            name = name,
            file = vFile,
            offset = offset
        ))
    }

    private fun hasServiceDerive(struct: RsStructItem): Boolean {
        return struct.outerAttrList
            .map { it.metaItem }
            .filter { it.name == "derive" }
            .any { deriveAttr ->
                deriveAttr.metaItemArgs?.metaItemList?.any { it.name == "Service" } == true
            }
    }

    // ── Components: Configuration ──

    private fun collectConfigurationComponent(
        vFile: VirtualFile, struct: RsStructItem,
        tomlConfigValues: Map<String, Map<String, String>>,
        out: MutableList<SpringRsComponentIndex.ComponentInfo>
    ) {
        val configPrefix = RustAttributeUtils.extractConfigPrefix(struct) ?: return
        val name = struct.name ?: return
        val offset = struct.identifier?.textOffset ?: struct.textOffset

        val tomlValues = tomlConfigValues[configPrefix] ?: emptyMap()
        val entries = SpringRsComponentIndex.buildConfigEntriesFromStruct(struct, tomlValues)

        out.add(SpringRsComponentIndex.ComponentInfo(
            type = SpringRsComponentIndex.ComponentType.CONFIGURATION,
            name = name,
            file = vFile,
            offset = offset,
            detail = configPrefix,
            configEntries = entries
        ))
    }

    // ── Components: Plugin ──

    private fun collectPluginComponent(
        vFile: VirtualFile, call: RsMethodCall,
        out: MutableList<SpringRsComponentIndex.ComponentInfo>
    ) {
        if (call.referenceName != "add_plugin") return
        val args = call.valueArgumentList.exprList
        if (args.isEmpty()) return

        val pluginExpr = args[0]
        val pluginName = extractPluginName(pluginExpr) ?: return
        val offset = call.textOffset

        out.add(SpringRsComponentIndex.ComponentInfo(
            type = SpringRsComponentIndex.ComponentType.PLUGIN,
            name = pluginName,
            file = vFile,
            offset = offset,
            detail = pluginExpr.text
        ))
    }

    private fun extractPluginName(expr: RsExpr): String? {
        return when (expr) {
            is RsPathExpr -> expr.path.referenceName
            is RsCallExpr -> {
                val callee = expr.expr
                if (callee is RsPathExpr) {
                    val path = callee.path
                    val qualifier = path.path
                    qualifier?.referenceName ?: path.referenceName
                } else null
            }
            else -> expr.text.takeIf { it.length < 80 }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Crate detection and filtering
    // ══════════════════════════════════════════════════════════════

    data class CrateInfo(val name: String, val rootPath: String)

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
     * Returns the set of crate root paths that have spring-rs dependencies.
     * Crates without spring dependencies are skipped during scanning.
     */
    private fun getSpringCrateRoots(project: Project, crateInfos: List<CrateInfo>): Set<String> {
        val springRoots = mutableSetOf<String>()
        val cargo = project.service<CargoProjectsService>()

        for (cargoProject in cargo.allProjects) {
            val workspace = cargoProject.workspace ?: continue
            for (pkg in workspace.packages) {
                if (pkg.origin != PackageOrigin.WORKSPACE) continue
                val root = pkg.contentRoot?.path ?: continue

                // Check if this package has spring-related dependencies.
                val hasSpring = pkg.dependencies.any { dep ->
                    val depName = dep.pkg.name
                    depName == "spring" || depName.startsWith("spring-")
                }
                if (hasSpring) {
                    springRoots.add(root)
                }
            }
        }

        return springRoots
    }

    fun findCrateRootForFile(crates: List<CrateInfo>, filePath: String): String? {
        var best: CrateInfo? = null
        for (c in crates) {
            if (filePath == c.rootPath || filePath.startsWith(c.rootPath.trimEnd('/') + "/")) {
                if (best == null || c.rootPath.length > best.rootPath.length) best = c
            }
        }
        return best?.rootPath
    }

    // ══════════════════════════════════════════════════════════════
    // ── TOML config value collection
    // ══════════════════════════════════════════════════════════════

    private fun collectTomlConfigValues(project: Project): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        val scope = GlobalSearchScope.projectScope(project)
        val tomlFileType = org.toml.lang.psi.TomlFileType
        val psiManager = PsiManager.getInstance(project)

        for (vFile in FileTypeIndex.getFiles(tomlFileType, scope)) {
            if (!SpringRsConfigFileUtil.isConfigFileName(vFile.name)) continue
            val tomlFile = psiManager.findFile(vFile) as? TomlFile ?: continue

            for (element in tomlFile.children) {
                if (element is TomlTable) {
                    val sectionName = element.header.key?.segments?.joinToString(".") { it.name ?: "" } ?: continue
                    val sectionValues = result.getOrPut(sectionName) { mutableMapOf() }
                    for (entry in element.entries) {
                        sectionValues[entry.key.text] = entry.value?.text ?: ""
                    }
                }
            }
        }

        return result
    }
}
