package com.springrs.plugin.wizard

import com.intellij.ide.util.projectWizard.AbstractNewProjectStep
import com.intellij.ide.util.projectWizard.ProjectSettingsStepBase
import com.intellij.platform.DirectoryProjectGenerator

/**
 * spring-rs Project Settings Step.
 *
 * Wraps the DirectoryProjectGenerator for the New Project dialog.
 */
open class SpringRsProjectSettingsStep(
    generator: DirectoryProjectGenerator<SpringRsConfigurationData>
) : ProjectSettingsStepBase<SpringRsConfigurationData>(generator, AbstractNewProjectStep.AbstractCallback())