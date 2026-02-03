package com.springrs.plugin.wizard

import org.rust.cargo.project.settings.ui.RustProjectSettingsPanel

/**
 * spring-rs Plugin item for selection.
 */
data class SpringRsSelectableItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String = "",
    val isSelected: Boolean = false
) {
    override fun toString(): String = name
}

/**
 * Configuration data collected from the New Project wizard.
 * Integrates with Rust plugin's RustProjectSettingsPanel.Data for toolchain settings.
 */
data class SpringRsConfigurationData(
    val rustSettings: RustProjectSettingsPanel.Data? = null,
    val selectedPlugins: List<String> = emptyList(),
    val generateExample: Boolean = true
) {
    companion object {
        val AVAILABLE_PLUGINS = SpringRsPluginRegistry.toSelectableItems()

        val DEFAULT_PLUGINS = listOf(SpringRsPluginRegistry.WEB)
    }
}