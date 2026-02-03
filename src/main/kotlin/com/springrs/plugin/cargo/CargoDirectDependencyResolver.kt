package com.springrs.plugin.cargo

import com.intellij.openapi.project.Project
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin

/**
 * Cargo direct dependency resolver.
 *
 * Resolves direct dependencies for:
 * - filtering transitive dependencies when parsing config structs
 * - checking whether `cfg(feature)` conditions are satisfied
 */
object CargoDirectDependencyResolver {

    /**
     * Returns all direct dependency packages of the project.
     */
    fun getDirectDependencies(project: Project): Set<CargoWorkspace.Package> {
        val directDeps = mutableSetOf<CargoWorkspace.Package>()

        project.cargoProjects.allProjects.forEach { cargoProject ->
            val workspace = cargoProject.workspace ?: return@forEach

            workspace.packages
                .filter { it.origin == PackageOrigin.WORKSPACE }
                .forEach { workspacePkg ->
                    workspacePkg.dependencies
                        .map { it.pkg }
                        .filter { it.origin == PackageOrigin.DEPENDENCY }
                        .forEach { directDeps.add(it) }
                }
        }

        return directDeps
    }

    /**
     * Returns true if the given package is a direct dependency of the project.
     */
    fun isDirectDependency(project: Project, pkg: CargoWorkspace.Package): Boolean {
        return pkg in getDirectDependencies(project)
    }

    /**
     * Returns true if a file's package belongs to direct dependencies (or workspace).
     *
     * @return true if the file belongs to a direct dependency or the workspace
     */
    fun isFileInDirectDependency(project: Project, pkg: CargoWorkspace.Package?): Boolean {
        if (pkg == null) return true

        // Workspace packages (project code) are always included.
        if (pkg.origin == PackageOrigin.WORKSPACE) return true

        return isDirectDependency(project, pkg)
    }

    /**
     * Returns enabled features for a dependency.
     *
     * @param packageName dependency package name (e.g. "spring-web")
     * @return enabled feature set
     */
    fun getDependencyEnabledFeatures(project: Project, packageName: String): Set<String> {
        val enabledFeatures = mutableSetOf<String>()

        project.cargoProjects.allProjects.forEach { cargoProject ->
            val workspace = cargoProject.workspace ?: return@forEach

            workspace.packages
                .filter { it.origin == PackageOrigin.WORKSPACE }
                .forEach { workspacePkg ->
                    workspacePkg.dependencies
                        .filter { it.pkg.name == packageName }
                        .forEach { dep ->
                            enabledFeatures.addAll(dep.requiredFeatures)
                        }
                }
        }

        return enabledFeatures
    }
}
