package com.springrs.plugin.utils

import com.intellij.openapi.project.Project
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.core.psi.rustPsiManager

/**
 * Spring project detector.
 *
 * Detects whether the current project is a spring-rs project.
 *
 * Detection logic:
 * 1. Check whether the project's Cargo dependencies contain a package named "spring"
 * 2. Also matches spring-web, spring-redis and other spring-related packages
 */
object SpringProjectDetector {

    /**
     * Returns true if the project is a spring-rs project.
     *
     * Uses a cached value to avoid repeated scanning.
     *
     * @param project IntelliJ project
     */
    fun isSpringProject(project: Project): Boolean {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val isSpring = detectSpringProject(project)
            CachedValueProvider.Result.create(
                isSpring,
                // Invalidate cache when Cargo dependencies change.
                project.rustPsiManager.rustStructureModificationTrackerInDependencies
            )
        }
    }

    /**
     * Actual detection logic.
     */
    private fun detectSpringProject(project: Project): Boolean {
        val cargoProjects = project.cargoProjects.allProjects

        if (cargoProjects.isEmpty()) {
            return false
        }

        // Keep this cheap: this code is often reached from EDT (icons/annotators).
        // Only inspect workspace packages (the user's crates) and their direct deps.
        for (cargoProject in cargoProjects) {
            val workspace = cargoProject.workspace ?: continue

            val workspacePkgs = workspace.packages.filter { it.origin == PackageOrigin.WORKSPACE }
            for (pkg in workspacePkgs) {
                // Package name itself (in case the project is a spring-* crate).
                if (isSpringDependency(pkg.name)) return true

                // Direct dependencies of the workspace packages.
                for (dependency in pkg.dependencies) {
                    if (isSpringDependency(dependency.pkg.name)) return true
                }
            }
        }

        return false
    }

    /**
     * Returns true if the dependency name looks Spring-related.
     *
     * @param name dependency name
     */
    private fun isSpringDependency(name: String): Boolean {
        // Exact match "spring" or prefix "spring-".
        return name == "spring" || name.startsWith("spring-") || name.contains("spring")
    }

    /**
     * Returns all Spring-related dependencies in the project.
     *
     * @param project IntelliJ project
     * @return list of Spring-related dependency names
     */
    fun getSpringDependencies(project: Project): List<String> {
        val dependencies = mutableSetOf<String>()
        val cargoProjects = project.cargoProjects.allProjects

        for (cargoProject in cargoProjects) {
            val workspace = cargoProject.workspace ?: continue

            val workspacePkgs = workspace.packages.filter { it.origin == PackageOrigin.WORKSPACE }
            for (pkg in workspacePkgs) {
                if (isSpringDependency(pkg.name)) dependencies.add(pkg.name)
                for (dependency in pkg.dependencies) {
                    val depName = dependency.pkg.name
                    if (isSpringDependency(depName)) dependencies.add(depName)
                }
            }
        }

        return dependencies.toList().sorted()
    }
}
