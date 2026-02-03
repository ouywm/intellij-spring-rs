package com.springrs.plugin.icons

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.SpringProjectDetector
import javax.swing.Icon

/**
 * Icon provider for spring-rs config files.
 *
 * Provides a custom icon for config files that match:
 * 1. filename is app.toml or app-{env}.toml
 * 2. project is a spring-rs project (has spring-related dependencies)
 *
 * Examples:
 * - app.toml ✓
 * - app-dev.toml ✓
 * - app-prod.toml ✓
 * - app-test.toml ✓
 * - app-staging.toml ✓
 * - config.toml ✗
 */
class SpringRsConfigFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        // No project context, can't decide.
        if (project == null) {
            return null
        }

        // Check filename pattern.
        if (!SpringRsConfigFileUtil.isConfigFileName(file.name)) {
            return null
        }

        // Check spring-rs project marker.
        if (!SpringProjectDetector.isSpringProject(project)) {
            return null
        }

        // Return spring-rs config file icon.
        return SpringRsIcons.SpringRs
    }
}
