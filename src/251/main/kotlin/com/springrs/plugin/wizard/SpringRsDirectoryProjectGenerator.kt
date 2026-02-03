package com.springrs.plugin.wizard

import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep
import com.intellij.ide.util.projectWizard.CustomStepProjectGenerator
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.welcomeScreen.AbstractActionWithPanel
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.ProjectGeneratorPeer
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import kotlinx.coroutines.CoroutineScope
import javax.swing.Icon

/**
 * spring-rs Directory Project Generator.
 *
 * Registers spring-rs in the New Project dialog for RustRover and other JetBrains IDEs.
 * Implements CustomStepProjectGenerator to correctly show settings UI in all IDEs.
 *
 * The CoroutineScope parameter is injected by the IntelliJ Platform framework,
 * following the same pattern as org.rust.ide.newProject.RsDirectoryProjectGenerator.
 */
class SpringRsDirectoryProjectGenerator(
    private val cs: CoroutineScope
) : DirectoryProjectGeneratorBase<SpringRsConfigurationData>(),
    CustomStepProjectGenerator<SpringRsConfigurationData> {

    private var peer: SpringRsProjectGeneratorPeer? = null

    override fun getName(): String = SpringRsBundle.message("wizard.name")

    override fun getLogo(): Icon = SpringRsIcons.SpringRsLogo

    override fun getDescription(): String = SpringRsBundle.message("wizard.description")

    override fun createPeer(): ProjectGeneratorPeer<SpringRsConfigurationData> =
        SpringRsProjectGeneratorPeer(cs = cs).also { peer = it }

    override fun validate(baseDirPath: String): ValidationResult {
        return ValidationResult.OK
    }

    override fun generateProject(
        project: Project,
        baseDir: VirtualFile,
        data: SpringRsConfigurationData,
        module: Module
    ) {
        // Generate project files
        val projectName = baseDir.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        SpringRsProjectGenerator.generate(baseDir, projectName, data)

        // Apply toolchain settings from data.rustSettings if available
        data.rustSettings?.let { rustSettings ->
            // The Rust plugin will handle toolchain configuration automatically
            // when it detects the Cargo.toml file
        }
    }

    override fun createStep(
        projectGenerator: DirectoryProjectGenerator<SpringRsConfigurationData>,
        callback: AbstractNewProjectStep.AbstractCallback<SpringRsConfigurationData>
    ): AbstractActionWithPanel = SpringRsProjectSettingsStep(projectGenerator)
}
