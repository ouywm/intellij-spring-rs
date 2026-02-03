package com.springrs.plugin.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import javax.swing.Icon

class SpringRsRunConfigurationType : ConfigurationType {

    private val factory = SpringRsRunConfigurationFactory(this)

    override fun getDisplayName(): String = SpringRsBundle.message("springrs.run.config.name")

    override fun getConfigurationTypeDescription(): String = SpringRsBundle.message("springrs.run.config.description")

    override fun getIcon(): Icon = SpringRsIcons.SpringRsLogo

    override fun getId(): String = "SpringRsRunConfigurationType"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)
}
