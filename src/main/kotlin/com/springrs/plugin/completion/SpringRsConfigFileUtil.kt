package com.springrs.plugin.completion

import com.intellij.psi.PsiElement
import com.springrs.plugin.utils.SpringProjectDetector
import org.toml.lang.psi.TomlFile

/**
 * spring-rs config file utilities.
 *
 * Used to determine whether a file is a spring-rs config file.
 *
 * Conditions:
 * 1. File name matches app.toml or app-{env}.toml
 * 2. Project is a spring-rs project (spring-related crates in dependencies)
 */
object SpringRsConfigFileUtil {

    /**
     * spring-rs config file name pattern.
     *
     * Supports multiple environments:
     * - app.toml: default config
     * - app-dev.toml: dev config
     * - app-prod.toml: prod config
     * - app-test.toml: test config
     * - app-{any}.toml: custom environment config
     */
    private val CONFIG_FILE_PATTERN = Regex("^app(-[a-zA-Z0-9_]+)?\\.toml$")

    /**
     * Determine whether an element is inside a spring-rs config file.
     *
     * Conditions:
     * 1. File name matches app.toml or app-{env}.toml
     * 2. Project is a spring-rs project
     *
     * @param element PSI element
     * @return true if it's in a spring-rs config file
     */
    fun isSpringRsConfigFile(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val fileName = file.name

        // Check file name.
        if (!isConfigFileName(fileName)) {
            return false
        }

        // Check whether this is a Spring project.
        val project = element.project
        return SpringProjectDetector.isSpringProject(project)
    }

    /**
     * Determine whether the given file is a spring-rs config file.
     *
     * @param file TOML file
     * @return true if it's a spring-rs config file
     */
    fun isSpringRsConfigFile(file: TomlFile): Boolean {
        // Check file name.
        if (!isConfigFileName(file.name)) {
            return false
        }

        // Check whether this is a Spring project.
        return SpringProjectDetector.isSpringProject(file.project)
    }

    /**
     * Check whether a file name matches spring-rs config file naming conventions.
     *
     * @param fileName file name
     * @return true if it matches app.toml or app-{env}.toml
     */
    fun isConfigFileName(fileName: String): Boolean {
        return CONFIG_FILE_PATTERN.matches(fileName)
    }
}
