package com.springrs.plugin.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project
import org.rust.cargo.runconfig.command.CargoCommandConfiguration

/**
 * spring-rs run configuration.
 *
 * Implementation-wise we reuse Rust plugin's CargoCommandConfiguration, but expose it under a
 * dedicated configuration type so users get a spring-rs entry/icon/grouping (like Solon).
 */
class SpringRsRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : CargoCommandConfiguration(project, name, factory)

