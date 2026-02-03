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
import javax.swing.Icon

/**
 * spring-rs Directory Project Generator — stub for platform 233.
 *
 * The real implementation lives in the 252 source set because it depends on
 * CoroutineScope injection which is not available in 233.
 *
 * This stub compiles but is never used at runtime because the wizard
 * extension point is disabled in plugin.xml.
 */
class SpringRsDirectoryProjectGenerator :
    DirectoryProjectGeneratorBase<SpringRsConfigurationData>(),
    CustomStepProjectGenerator<SpringRsConfigurationData> {

    override fun getName(): String = SpringRsBundle.message("wizard.name")

    override fun getLogo(): Icon = SpringRsIcons.SpringRsLogo

    override fun getDescription(): String = SpringRsBundle.message("wizard.description")

    override fun createPeer(): ProjectGeneratorPeer<SpringRsConfigurationData> =
        SpringRsProjectGeneratorPeer()

    override fun validate(baseDirPath: String): ValidationResult = ValidationResult.OK

    override fun generateProject(
        project: Project,
        baseDir: VirtualFile,
        data: SpringRsConfigurationData,
        module: Module
    ) {
        val projectName = baseDir.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        SpringRsProjectGenerator.generate(baseDir, projectName, data)
    }

    override fun createStep(
        projectGenerator: DirectoryProjectGenerator<SpringRsConfigurationData>,
        callback: AbstractNewProjectStep.AbstractCallback<SpringRsConfigurationData>
    ): AbstractActionWithPanel = SpringRsProjectSettingsStep(projectGenerator)
}