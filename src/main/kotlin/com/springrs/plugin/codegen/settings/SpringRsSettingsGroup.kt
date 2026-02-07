package com.springrs.plugin.codegen.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Top-level settings group: Settings → spring-rs
 *
 * Acts as a parent node for all spring-rs settings.
 * Shows plugin info when selected directly.
 */
class SpringRsSettingsGroup : SearchableConfigurable, Configurable.Composite {

    override fun getId(): String = SETTINGS_ID

    override fun getDisplayName(): String = "spring-rs"

    override fun getConfigurables(): Array<Configurable> = emptyArray()

    override fun createComponent(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
            add(JBLabel("spring-rs Plugin Settings").apply {
                font = font.deriveFont(Font.BOLD, 16f)
            }, BorderLayout.NORTH)
            add(JBLabel(SpringRsBundle.message("codegen.settings.group.description")).apply {
                border = JBUI.Borders.emptyTop(10)
            }, BorderLayout.CENTER)
        }
    }

    override fun isModified(): Boolean = false
    override fun apply() {}

    companion object {
        const val SETTINGS_ID = "SpringRs.Settings"
    }
}
