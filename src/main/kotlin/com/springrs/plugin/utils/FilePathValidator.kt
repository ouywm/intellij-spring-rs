package com.springrs.plugin.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil

/**
 * File path validator.
 *
 * Centralized file path checks used to decide whether a file should be processed.
 */
object FilePathValidator {

    /**
     * Returns true if the path points to a macro-expanded file.
     *
     * @param filePath file path
     */
    fun isMacroExpanded(filePath: String): Boolean {
        return filePath.contains("/rust_expanded_macros/")
    }

    /**
     * Returns true if the path points to Rust standard library sources.
     *
     * @param filePath file path
     */
    fun isStandardLibrary(filePath: String): Boolean {
        return filePath.contains("/rustlib/src/rust/") ||
            filePath.contains("/library/") && (
                filePath.contains("/std/") ||
                    filePath.contains("/core/") ||
                    filePath.contains("/alloc/")
                )
    }

    /**
     * Returns true if the path is related to spring-rs (used for dependency filtering).
     *
     * @param filePath file path
     */
    fun isSpringRelated(filePath: String): Boolean {
        return filePath.contains(SpringRsConstants.FRAMEWORK_NAME, ignoreCase = true)
    }

    /**
     * Returns true if the path is inside the current project (and not in dependency directories).
     *
     * @param project current project
     * @param filePath file path
     */
    fun isProjectFile(project: Project, filePath: String): Boolean {
        // Project root directory
        val projectBasePath = project.basePath ?: return false

        // Check if the file is under the project root
        if (!filePath.startsWith(projectBasePath)) {
            return false
        }

        // Exclude common dependency/output directories (target, cargo registry, git checkouts, etc.)
        return !filePath.contains("/target/") &&
               !filePath.contains("/.cargo/") &&
               !filePath.contains("/registry/") &&
               !filePath.contains("/git/checkouts/")
    }

    /**
     * Returns true if the path is a valid external file.
     *
     * A valid external file is not macro-expanded and not from the standard library.
     *
     * @param filePath file path
     */
    fun isValidExternalFile(filePath: String): Boolean {
        return !isMacroExpanded(filePath) && !isStandardLibrary(filePath)
    }

    /**
     * Returns true if the file should be processed.
     *
     * A file should be processed if it is not macro-expanded, not standard library,
     * and is considered Spring-related.
     *
     * @param filePath file path
     */
    fun shouldProcess(filePath: String): Boolean {
        // Exclude macro-expanded and standard library files
        if (isMacroExpanded(filePath) || isStandardLibrary(filePath)) {
            return false
        }

        // Only handle spring-related dependencies
        return isSpringRelated(filePath)
    }
}
