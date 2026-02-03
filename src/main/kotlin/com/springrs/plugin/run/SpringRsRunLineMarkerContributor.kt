package com.springrs.plugin.run

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.springrs.plugin.compat.RunLineMarkerCompat
import com.springrs.plugin.icons.SpringRsIcons
import com.springrs.plugin.utils.SpringProjectDetector
import org.rust.cargo.project.model.cargoProjects
import org.rust.lang.core.psi.RsFunction

/**
 * Replaces Rust plugin's default run gutter icon for spring-rs applications with a spring-rs icon.
 *
 * Note: this is implemented via RunLineMarkerContributor (not LineMarkerProvider) so we integrate
 * into IntelliJ's "Run" gutter pipeline and avoid showing two run icons.
 */
class SpringRsRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        val project = element.project
        if (DumbService.isDumb(project)) return null

        // Match Rust plugin's fast-path: run markers are anchored on the identifier leaf.
        if (!RunLineMarkerCompat.isIdentifier(element)) return null

        val function = element.parent as? RsFunction ?: return null
        if (function.identifier != element) return null
        if (function.name != "main") return null

        val vFile = function.containingFile?.virtualFile ?: return null
        if (!isCrateRoot(project, vFile)) return null

        if (!SpringProjectDetector.isSpringProject(project)) return null

        // Keep standard Run/Debug actions; our RunConfigurationProducer makes them create spring-rs configs.
        val actions = ExecutorAction.getActions(0)
        return RunLineMarkerCompat.createInfo(SpringRsIcons.SpringRsLogo, actions)
    }

    private fun isCrateRoot(project: Project, file: VirtualFile): Boolean {
        for (cargoProject in project.cargoProjects.allProjects) {
            val workspace = cargoProject.workspace ?: continue
            if (workspace.isCrateRoot(file)) return true
        }
        return false
    }
}

