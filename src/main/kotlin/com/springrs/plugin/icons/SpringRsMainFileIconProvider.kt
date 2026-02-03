package com.springrs.plugin.icons

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.springrs.plugin.utils.SpringProjectDetector
import javax.swing.Icon

/**
 * Gives the Rust entrypoint file (main.rs) a spring-rs icon in spring-rs projects.
 */
class SpringRsMainFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (project == null) return null
        if (file.name != "main.rs") return null
        if (!SpringProjectDetector.isSpringProject(project)) return null
        return SpringRsIcons.SpringRsLogo
    }
}

