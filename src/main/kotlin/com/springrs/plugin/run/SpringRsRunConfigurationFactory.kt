package com.springrs.plugin.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.compat.RunConfigCompat

class SpringRsRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = "SpringRs"

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return SpringRsRunConfiguration(project, this, SpringRsBundle.message("springrs.run.config.name"))
    }

    override fun getOptionsClass(): Class<out BaseState> {
        // Use CargoConfigurationOptions if available (251+), otherwise fall back to default.
        return RunConfigCompat.getOptionsClass() ?: RunConfigurationOptions::class.java
    }
}
