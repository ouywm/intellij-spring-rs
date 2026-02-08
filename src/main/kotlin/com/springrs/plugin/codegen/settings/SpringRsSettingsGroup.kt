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

            val content = JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                border = JBUI.Borders.emptyTop(16)

                add(JBLabel(SpringRsBundle.message("codegen.settings.group.description")).apply {
                    border = JBUI.Borders.emptyBottom(16)
                })

                // Sub-pages summary
                add(JBLabel("<html><b>Code Generation</b> — Velocity templates, template groups, global variables</html>"))
                add(JBLabel("  ├── General — Database type, output dirs, prefix, formatting"))
                add(JBLabel("  └── Type Mapping — SQL → Rust type mapping (regex, groups, import/export)"))
                add(javax.swing.Box.createVerticalStrut(16))
                add(JBLabel("<html><b>Features:</b></html>"))
                add(JBLabel("  • TOML config completion, validation & navigation"))
                add(JBLabel("  • HTTP route management & tool window"))
                add(JBLabel("  • Service / Inject line markers"))
                add(JBLabel("  • Sea-ORM code generation from database tables"))
                add(JBLabel("  • JSON to Rust struct conversion"))
            }
            add(content, BorderLayout.CENTER)
        }
    }

    override fun isModified(): Boolean = false
    override fun apply() {}

    companion object {
        const val SETTINGS_ID = "SpringRs.Settings"
    }
}
