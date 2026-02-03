package com.springrs.plugin.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.utils.SpringProjectDetector
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RustChannel
import org.rust.lang.core.psi.RsFunction

/**
 * Produces spring-rs run configurations from context (gutter / editor / project view),
 * so the standard Run/Debug actions create a spring-rs configuration instead of a plain Cargo one.
 */
class SpringRsRunConfigurationProducer : LazyRunConfigurationProducer<SpringRsRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory {
        return ConfigurationTypeUtil.findConfigurationType(SpringRsRunConfigurationType::class.java)
            .configurationFactories
            .first()
    }

    override fun setupConfigurationFromContext(
        configuration: SpringRsRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val info = computeContextInfo(context) ?: return false

        configuration.name = info.name
        configuration.setFromCmd(info.cmd)
        sourceElement.set(info.sourceElement)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: SpringRsRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val info = computeContextInfo(context) ?: return false
        return configuration.canBeFrom(info.cmd)
    }

    private data class ContextInfo(
        val sourceElement: PsiElement,
        val cmd: CargoCommandLine,
        val name: String
    )

    private fun computeContextInfo(context: ConfigurationContext): ContextInfo? {
        val location = context.location ?: return null
        val element = location.psiElement ?: return null
        val project = element.project

        if (DumbService.isDumb(project)) return null
        if (!SpringProjectDetector.isSpringProject(project)) return null

        val function = PsiTreeUtil.getParentOfType(element, RsFunction::class.java) ?: return null
        if (function.name != "main") return null

        val identifier = function.identifier ?: return null

        val crateRoot = function.containingFile?.virtualFile ?: return null
        if (!isCrateRoot(project, crateRoot)) return null

        val target = findTargetByCrateRoot(project, crateRoot) ?: return null
        val cmd = CargoCommandLine.forTarget(
            target,
            "run",
            emptyList(),
            RustChannel.DEFAULT,
            EnvironmentVariablesData.DEFAULT,
            false
        )

        val name = "${target.name} (spring-rs)"
        return ContextInfo(identifier, cmd, name)
    }

    private fun isCrateRoot(project: Project, file: VirtualFile): Boolean {
        for (cargoProject in project.cargoProjects.allProjects) {
            val workspace = cargoProject.workspace ?: continue
            if (workspace.isCrateRoot(file)) return true
        }
        return false
    }

    private fun findTargetByCrateRoot(project: Project, crateRoot: VirtualFile): CargoWorkspace.Target? {
        for (cargoProject in project.cargoProjects.allProjects) {
            val workspace = cargoProject.workspace ?: continue
            val target = workspace.findTargetByCrateRoot(crateRoot)
            if (target != null) return target
        }
        return null
    }
}

