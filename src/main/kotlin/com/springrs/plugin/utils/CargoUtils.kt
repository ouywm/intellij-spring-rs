package com.springrs.plugin.utils

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin

/**
 * Cargo/crate-related utilities.
 */
object CargoUtils {

    data class CargoScope(
        val currentPackageName: String,
        val allowedPackageNames: Set<String>
    )

    /**
     * Crate information.
     */
    data class CrateInfo(
        val name: String,
        val rootPath: String
    )

    /**
     * Returns all workspace crates.
     */
    fun getWorkspaceCrates(project: Project): List<CrateInfo> {
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
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    /**
     * Finds the crate root for a file path.
     *
     * @param project project
     * @param filePath file path
     * @return crate root path, or null if not found
     */
    fun findCrateRootForFile(project: Project, filePath: String): String? {
        val crates = getWorkspaceCrates(project)
        return findCrateRootForFile(crates, filePath)
    }

    /**
     * Finds the crate root for a file path (using a precomputed crate list).
     *
     * @param crates crate list
     * @param filePath file path
     * @return crate root path, or null if not found
     */
    fun findCrateRootForFile(crates: List<CrateInfo>, filePath: String): String? {
        var best: CrateInfo? = null
        for (c in crates) {
            if (filePath == c.rootPath || filePath.startsWith(c.rootPath.trimEnd('/') + "/")) {
                if (best == null || c.rootPath.length > best.rootPath.length) best = c
            }
        }
        return best?.rootPath
    }

    fun findPackageNameForFile(project: Project, file: VirtualFile): String? {
        return findPackageForVirtualFile(project, file)?.name
    }

    fun getCargoScopeForFile(project: Project, file: VirtualFile): CargoScope? {
        val currentPkg = findPackageForVirtualFile(project, file) ?: return null
        return CargoScope(
            currentPackageName = currentPkg.name,
            allowedPackageNames = collectDependencyPackageNames(currentPkg)
        )
    }

    /**
     * Find Cargo package for a file.
     *
     * `cargoProjects.findPackageForFile` can return null for non-Rust files (e.g. TOML) in some setups.
     * As a fallback, we match by the longest package contentRoot that is a prefix of the file path.
     */
    private fun findPackageForVirtualFile(project: Project, file: VirtualFile): CargoWorkspace.Package? {
        project.cargoProjects.findPackageForFile(file)?.let { return it }

        val filePath = file.path
        var best: CargoWorkspace.Package? = null
        var bestRootLen = -1

        project.cargoProjects.allProjects
            .asSequence()
            .mapNotNull { it.workspace }
            .flatMap { it.packages.asSequence() }
            .forEach { pkg ->
                val root = pkg.contentRoot?.path ?: return@forEach
                val normalizedRoot = root.trimEnd('/')
                val matches = filePath == normalizedRoot || filePath.startsWith("$normalizedRoot/")
                if (!matches) return@forEach

                if (normalizedRoot.length > bestRootLen) {
                    best = pkg
                    bestRootLen = normalizedRoot.length
                }
            }

        return best
    }

    /**
     * Collect package names for the current package and all its transitive dependencies (BFS).
     */
    private fun collectDependencyPackageNames(pkg: CargoWorkspace.Package): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<CargoWorkspace.Package>()
        queue.add(pkg)
        visited.add(pkg.name)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (dep in current.dependencies) {
                val depPkg = dep.pkg
                if (visited.add(depPkg.name)) {
                    queue.add(depPkg)
                }
            }
        }

        return visited
    }

    /**
     * Finds crate info for a file path.
     *
     * @param project project
     * @param filePath file path
     * @return crate info, or null if not found
     */
    fun findCrateForFile(project: Project, filePath: String): CrateInfo? {
        val crates = getWorkspaceCrates(project)
        return findCrateForFile(crates, filePath)
    }

    /**
     * Finds crate info for a file path (using a precomputed crate list).
     *
     * @param crates crate list
     * @param filePath file path
     * @return crate info, or null if not found
     */
    fun findCrateForFile(crates: List<CrateInfo>, filePath: String): CrateInfo? {
        var best: CrateInfo? = null
        for (c in crates) {
            if (filePath == c.rootPath || filePath.startsWith(c.rootPath.trimEnd('/') + "/")) {
                if (best == null || c.rootPath.length > best.rootPath.length) best = c
            }
        }
        return best
    }
}
