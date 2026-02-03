package com.springrs.plugin.wizard

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.Panel
import java.nio.file.Path
import java.nio.file.Paths

/**
 * spring-rs New Project Panel — stub for platform 233.
 *
 * The real implementation lives in the 252 source set because it depends on
 * [org.rust.cargo.project.settings.ui.RustProjectSettingsPanel] APIs
 * (coroutineScope constructor parameter, start() method) that do not exist in 241.
 *
 * This stub compiles but is never used at runtime because the wizard
 * extension point is disabled in plugin.xml.
 */
class SpringRsNewProjectPanel(
    cargoProjectDir: Path = Paths.get("."),
    private val updateListener: (() -> Unit)? = null
) : Disposable {

    val data: SpringRsConfigurationData
        get() = SpringRsConfigurationData(
            rustSettings = null,
            selectedPlugins = emptyList(),
            generateExample = false
        )

    fun attachTo(panel: Panel) {
        // No-op in 241
    }

    @Throws(ConfigurationException::class)
    fun validateSettings() {
        // No-op in 241
    }

    fun start(stateForComponent: ModalityState) {
        // No-op in 241
    }

    override fun dispose() {}
}