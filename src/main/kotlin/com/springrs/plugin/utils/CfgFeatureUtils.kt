package com.springrs.plugin.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.springrs.plugin.cargo.CargoDirectDependencyResolver
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.existsAfterExpansion
import org.rust.lang.core.psi.ext.name

/**
 * Cfg/feature utilities.
 *
 * Helpers for dealing with cfg-based conditional compilation.
 */
object CfgFeatureUtils {

    /**
     * Returns true if the struct should be included (based on cfg/feature conditions).
     *
     * - Workspace packages: use `existsAfterExpansion`
     * - Dependency packages: check enabled features from dependency configuration
     *
     * @param struct Rust struct
     */
    fun shouldIncludeStruct(struct: RsStructItem): Boolean {
        val crate = struct.containingCrate ?: return true
        val virtualFile = struct.containingFile?.virtualFile
        val project = struct.project

        // Resolve cargo package for this file
        val cargoPackage = virtualFile?.let { project.cargoProjects.findPackageForFile(it) }

        return if (cargoPackage?.origin == PackageOrigin.DEPENDENCY) {
            // Dependency package: evaluate cfg(feature = ...)
            val cfgAttr = extractCfgAttribute(struct)
            checkDependencyFeature(cargoPackage, cfgAttr, project)
        } else {
            // Workspace package: rely on Rust plugin API
            struct.existsAfterExpansion(crate)
        }
    }

    /**
     * Extracts the `cfg` attribute text from a struct, if any.
     */
    private fun extractCfgAttribute(struct: RsStructItem): String? {
        return struct.outerAttrList
            .map { it.metaItem }
            .find { it.name == "cfg" }
            ?.text
    }

    /**
     * Returns true if the element satisfies cfg conditions.
     */
    fun checkCfgCondition(
        element: RsElement,
        crate: Crate,
        cfgAttr: String?,
        project: Project,
        virtualFile: VirtualFile?
    ): Boolean {
        val cargoPackage = virtualFile?.let { project.cargoProjects.findPackageForFile(it) }

        return if (cargoPackage?.origin == PackageOrigin.DEPENDENCY) {
            checkDependencyFeature(cargoPackage, cfgAttr, project)
        } else {
            element.existsAfterExpansion(crate)
        }
    }

    /**
     * Evaluates feature condition for a dependency package.
     */
    private fun checkDependencyFeature(
        cargoPackage: CargoWorkspace.Package,
        cfgAttr: String?,
        project: Project
    ): Boolean {
        if (cfgAttr == null) return true

        val enabledFeatures = CargoDirectDependencyResolver.getDependencyEnabledFeatures(
            project,
            cargoPackage.name
        )

        val requiredFeature = extractFeatureFromCfg(cfgAttr)
        return requiredFeature == null || requiredFeature in enabledFeatures
    }

    /**
     * Extracts the feature name from a cfg attribute string.
     */
    fun extractFeatureFromCfg(cfgAttr: String?): String? {
        if (cfgAttr == null) return null
        val featurePattern = Regex("""feature\s*=\s*["']([^"']+)["']""")
        return featurePattern.find(cfgAttr)?.groupValues?.get(1)
    }

    /**
     * Resolves the cargo package for a given struct.
     */
    fun getCargoPackage(struct: RsStructItem): CargoWorkspace.Package? {
        val virtualFile = struct.containingFile?.virtualFile ?: return null
        return struct.project.cargoProjects.findPackageForFile(virtualFile)
    }

    /**
     * Returns true if the package is a dependency package.
     */
    fun isDependencyPackage(cargoPackage: CargoWorkspace.Package?): Boolean {
        return cargoPackage?.origin == PackageOrigin.DEPENDENCY
    }
}
