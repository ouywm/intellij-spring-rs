package com.springrs.plugin.wizard

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.Panel
import kotlinx.coroutines.CoroutineScope
import org.rust.cargo.project.settings.ui.RustProjectSettingsPanel
import java.nio.file.Path
import java.nio.file.Paths

/**
 * spring-rs New Project Panel.
 *
 * Integrates with Rust plugin's RustProjectSettingsPanel for toolchain settings,
 * and adds spring-rs plugin selection.
 */
class SpringRsNewProjectPanel(
    cargoProjectDir: Path = Paths.get("."),
    private val cs: CoroutineScope,
    private val updateListener: (() -> Unit)? = null
) : Disposable {

    /**
     * Rust toolchain settings panel from the Rust plugin.
     * Provides: toolchain location, toolchain version, standard library, environment variables.
     */
    private val rustProjectSettings = RustProjectSettingsPanel(
        _cargoProjectDir = cargoProjectDir,
        coroutineScope = cs,
        updateListener = { updateListener?.invoke() }
    )

    init {
        Disposer.register(this, rustProjectSettings)
    }

    /**
     * spring-rs plugin selection panel.
     */
    private val pluginSelectionPanel = SpringRsPluginSelectionPanel(
        SpringRsConfigurationData.AVAILABLE_PLUGINS,
        SpringRsConfigurationData.DEFAULT_PLUGINS
    ) { updateListener?.invoke() }

    /**
     * Get the current configuration data.
     */
    val data: SpringRsConfigurationData
        get() = SpringRsConfigurationData(
            rustSettings = rustProjectSettings.data,
            selectedPlugins = pluginSelectionPanel.selectedPlugins,
            generateExample = pluginSelectionPanel.generateExample
        )

    /**
     * Attach this panel to a Kotlin UI DSL panel.
     */
    fun attachTo(panel: Panel) {
        // Rust toolchain settings (from Rust plugin)
        rustProjectSettings.attachTo(panel)

        // Plugin selection (no group title)
        panel.row {
            cell(pluginSelectionPanel)
                .resizableColumn()
        }.resizableRow()
    }

    /**
     * Validate settings before project creation.
     */
    @Throws(ConfigurationException::class)
    fun validateSettings() {
        rustProjectSettings.validateSettings()
    }

    /**
     * Start background tasks (toolchain detection, etc.).
     */
    fun start(stateForComponent: ModalityState) {
        rustProjectSettings.start(stateForComponent)
    }

    override fun dispose() {
        // Children are automatically disposed via Disposer
    }
}