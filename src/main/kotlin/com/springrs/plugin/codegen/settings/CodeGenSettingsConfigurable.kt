package com.springrs.plugin.codegen.settings

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.codegen.template.TemplateGroup
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * Settings page for Sea-ORM code generation.
 *
 * Registered as a project-level configurable in `database-features.xml`.
 * Appears under: Settings → Tools → Sea-ORM Code Generation
 *
 * Tabs:
 * - **Templates**: Edit Velocity templates per layer (entity/dto/vo/service/route)
 * - **Global Variables**: Define variables accessible in all templates ($author, etc.)
 */
class CodeGenSettingsConfigurable(private val project: Project) : Configurable {

    private var mainPanel: JPanel? = null
    private var templateEditor: EditorTextField? = null
    private var templateList: JBList<String>? = null
    private var globalVarsEditor: EditorTextField? = null

    /** Working copy of the template group (edits happen here, saved on Apply). */
    private var workingGroup: TemplateGroup = TemplateGroup.createDefault()

    /** Currently selected template name in the list. */
    private var selectedTemplateName: String? = null

    override fun getDisplayName(): String = SpringRsBundle.message("codegen.settings.title")

    override fun createComponent(): JComponent {
        val tabs = JBTabbedPane()

        // ── Tab 1: Templates ──
        tabs.addTab(SpringRsBundle.message("codegen.settings.tab.templates"), createTemplatesTab())

        // ── Tab 2: Global Variables ──
        tabs.addTab(SpringRsBundle.message("codegen.settings.tab.global"), createGlobalVarsTab())

        mainPanel = JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER) }
        return mainPanel!!
    }

    /**
     * Templates tab: left = template list, right = Velocity editor.
     */
    private fun createTemplatesTab(): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.border = JBUI.Borders.empty(8)

        // Left: template list
        val listModel = DefaultListModel<String>()
        TemplateGroup.TEMPLATE_NAMES.forEach { listModel.addElement("$it.rs.vm") }
        templateList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        val listPanel = JPanel(BorderLayout())
        listPanel.preferredSize = Dimension(160, 0)
        listPanel.add(JBLabel(SpringRsBundle.message("codegen.settings.template.list")).apply {
            border = JBUI.Borders.emptyBottom(4)
        }, BorderLayout.NORTH)
        listPanel.add(JScrollPane(templateList), BorderLayout.CENTER)

        // Right: template editor
        val document = EditorFactory.getInstance().createDocument("")
        templateEditor = object : EditorTextField(document, project, PlainTextFileType.INSTANCE, false, false) {
            override fun createEditor(): EditorEx = super.createEditor().also { editor ->
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                editor.settings.apply {
                    isLineNumbersShown = true
                    isUseSoftWraps = true
                    setTabSize(4)
                }
            }
        }

        val editorPanel = JPanel(BorderLayout())
        editorPanel.add(JBLabel(SpringRsBundle.message("codegen.settings.template.editor")).apply {
            border = JBUI.Borders.emptyBottom(4)
        }, BorderLayout.NORTH)
        editorPanel.add(templateEditor!!, BorderLayout.CENTER)

        // Hint panel below editor
        val hintPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel(SpringRsBundle.message("codegen.settings.template.hint")))
        }
        editorPanel.add(hintPanel, BorderLayout.SOUTH)

        panel.add(listPanel, BorderLayout.WEST)
        panel.add(editorPanel, BorderLayout.CENTER)

        // Load template content when selection changes
        templateList!!.addListSelectionListener {
            saveCurrentTemplate()
            val idx = templateList!!.selectedIndex
            if (idx >= 0) {
                val displayName = templateList!!.model.getElementAt(idx)
                val name = displayName.removeSuffix(".rs.vm")
                selectedTemplateName = name
                templateEditor!!.text = workingGroup.templates[name] ?: ""
            }
        }

        // Select first template
        if (listModel.size() > 0) {
            templateList!!.selectedIndex = 0
        }

        return panel
    }

    /**
     * Global variables tab: key=value editor (one per line).
     */
    private fun createGlobalVarsTab(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        panel.add(JBLabel(SpringRsBundle.message("codegen.settings.global.hint")).apply {
            border = JBUI.Borders.emptyBottom(8)
        }, BorderLayout.NORTH)

        val document = EditorFactory.getInstance().createDocument("")
        globalVarsEditor = object : EditorTextField(document, project, PlainTextFileType.INSTANCE, false, false) {
            override fun createEditor(): EditorEx = super.createEditor().also { editor ->
                editor.setVerticalScrollbarVisible(true)
                editor.settings.apply {
                    isLineNumbersShown = true
                    isUseSoftWraps = true
                }
            }
        }
        panel.add(globalVarsEditor!!, BorderLayout.CENTER)

        return panel
    }

    // ── Save / Load ──

    private fun saveCurrentTemplate() {
        val name = selectedTemplateName ?: return
        val content = templateEditor?.text ?: return
        workingGroup.templates[name] = content
    }

    override fun isModified(): Boolean = true // Always allow Apply

    override fun apply() {
        saveCurrentTemplate()
        // Save global vars
        val varsText = globalVarsEditor?.text ?: ""
        workingGroup.globalVariables.clear()
        varsText.lines().forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.isNotEmpty()) workingGroup.globalVariables[key] = value
            }
        }
        // TODO: persist workingGroup to CodeGenSettingsState when template group storage is added
    }

    override fun reset() {
        workingGroup = TemplateGroup.createDefault()
        selectedTemplateName = null
        templateList?.selectedIndex = 0

        // Load global vars
        val text = workingGroup.globalVariables.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        globalVarsEditor?.text = text
    }

    override fun disposeUIResources() {
        mainPanel = null
        templateEditor = null
        templateList = null
        globalVarsEditor = null
    }
}
