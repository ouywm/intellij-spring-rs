package com.springrs.plugin.codegen.settings

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.codegen.CodeGenSettingsState
import com.springrs.plugin.codegen.VelocityTemplateEngine
import com.springrs.plugin.codegen.template.TemplateGroup
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.*

/** Add item to ComboBox only if not already present. */
private fun <T> ComboBox<T>.addIfAbsent(item: T) {
    if ((0 until itemCount).none { getItemAt(it) == item }) addItem(item)
}

/**
 * Settings page for Sea-ORM code generation templates and groups.
 *
 * Registered as a project-level configurable in `database-features.xml`.
 * Appears under: Settings → spring-rs → Code Generation
 *
 * Tabs:
 * - **Template Groups**: Manage template groups (CRUD + import/export JSON)
 * - **Templates**: Edit Velocity templates per layer (entity/dto/vo/service/route)
 * - **Global Variables**: Define variables accessible in all templates ($author, etc.)
 */
class CodeGenSettingsConfigurable(private val project: Project) : Configurable {

    private var mainPanel: JPanel? = null
    private var templateList: JBList<String>? = null
    private var globalVarsEditor: EditorTextField? = null

    // Full editors (with built-in scrolling)
    private var templateEditorInstance: Editor? = null
    private var previewEditorInstance: Editor? = null
    private var previewErrorLabel: JBLabel? = null

    // Template Groups
    private var groupCombo: ComboBox<String>? = null

    /** Working copy: group name → TemplateGroup */
    private val workingGroups: MutableMap<String, TemplateGroup> = mutableMapOf()

    /** Currently active group name. */
    private var activeGroupName: String = "Default"

    /** Currently selected template name in the list. */
    private var selectedTemplateName: String? = null

    override fun getDisplayName(): String = SpringRsBundle.message("codegen.settings.title")

    override fun createComponent(): JComponent {
        val tabs = JBTabbedPane()

        // ── Tab 1: Template Groups ──
        tabs.addTab(SpringRsBundle.message("codegen.settings.tab.groups"), createGroupsTab())

        // ── Tab 2: Templates ──
        tabs.addTab(SpringRsBundle.message("codegen.settings.tab.templates"), createTemplatesTab())

        // ── Tab 3: Global Variables ──
        tabs.addTab(SpringRsBundle.message("codegen.settings.tab.global"), createGlobalVarsTab())

        mainPanel = JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER) }

        // Load data
        loadFromSettings()

        return mainPanel!!
    }

    // ══════════════════════════════════════════════════════════════
    // ── Tab 1: Template Groups ──
    // ══════════════════════════════════════════════════════════════

    private fun createGroupsTab(): JPanel {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = JBUI.Borders.empty(8)

        // ── Top: group selector + action buttons ──
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        topPanel.add(JBLabel(SpringRsBundle.message("codegen.settings.groups.label")))

        groupCombo = ComboBox<String>()
        topPanel.add(groupCombo!!)

        // Add group
        val addBtn = JButton("+")
        addBtn.toolTipText = SpringRsBundle.message("codegen.settings.groups.add")
        addBtn.addActionListener { addGroup() }
        topPanel.add(addBtn)

        // Copy group
        val copyBtn = JButton(SpringRsBundle.message("codegen.settings.groups.copy"))
        copyBtn.addActionListener { copyGroup() }
        topPanel.add(copyBtn)

        // Delete group
        val deleteBtn = JButton(SpringRsBundle.message("codegen.settings.groups.delete"))
        deleteBtn.addActionListener { deleteGroup() }
        topPanel.add(deleteBtn)

        // Import JSON
        val importBtn = JButton(SpringRsBundle.message("codegen.settings.groups.import"))
        importBtn.addActionListener { importGroupFromJson() }
        topPanel.add(importBtn)

        // Export JSON
        val exportBtn = JButton(SpringRsBundle.message("codegen.settings.groups.export"))
        exportBtn.addActionListener { exportGroupToJson() }
        topPanel.add(exportBtn)

        panel.add(topPanel, BorderLayout.NORTH)

        // ── Center: info ──
        val infoPanel = JPanel(BorderLayout())
        infoPanel.border = JBUI.Borders.empty(16)
        infoPanel.add(
            JBLabel(SpringRsBundle.message("codegen.settings.groups.info")).apply {
                verticalAlignment = SwingConstants.TOP
            }, BorderLayout.CENTER
        )
        panel.add(infoPanel, BorderLayout.CENTER)

        // ── Group change listener ──
        groupCombo!!.addActionListener {
            val selected = groupCombo!!.selectedItem as? String ?: return@addActionListener
            if (selected != activeGroupName) {
                saveCurrentGroupData()
                activeGroupName = selected
                loadGroupData(activeGroupName)
            }
        }

        return panel
    }

    // ── Group Actions ──

    private fun addGroup() {
        val name = Messages.showInputDialog(
            project,
            SpringRsBundle.message("codegen.settings.groups.add.prompt"),
            SpringRsBundle.message("codegen.settings.groups.add"),
            Messages.getQuestionIcon()
        ) ?: return

        if (name.isBlank()) return
        if (workingGroups.containsKey(name)) {
            Messages.showWarningDialog(
                project,
                SpringRsBundle.message("codegen.settings.groups.exists", name),
                SpringRsBundle.message("codegen.settings.groups.add")
            )
            return
        }

        val newGroup = TemplateGroup.createDefault()
        newGroup.name = name
        workingGroups[name] = newGroup
        groupCombo?.addItem(name)
        groupCombo?.selectedItem = name
    }

    private fun copyGroup() {
        val name = Messages.showInputDialog(
            project,
            SpringRsBundle.message("codegen.settings.groups.copy.prompt"),
            SpringRsBundle.message("codegen.settings.groups.copy"),
            Messages.getQuestionIcon(),
            "${activeGroupName} Copy",
            null
        ) ?: return

        if (name.isBlank()) return
        if (workingGroups.containsKey(name)) {
            Messages.showWarningDialog(
                project,
                SpringRsBundle.message("codegen.settings.groups.exists", name),
                SpringRsBundle.message("codegen.settings.groups.copy")
            )
            return
        }

        saveCurrentGroupData()
        val source = workingGroups[activeGroupName] ?: return
        val copied = TemplateGroup(name)
        copied.templates.putAll(source.templates)
        copied.globalVariables.putAll(source.globalVariables)
        workingGroups[name] = copied
        groupCombo?.addItem(name)
        groupCombo?.selectedItem = name
    }

    private fun deleteGroup() {
        if (activeGroupName == "Default") {
            Messages.showWarningDialog(
                project,
                SpringRsBundle.message("codegen.settings.groups.delete.default"),
                SpringRsBundle.message("codegen.settings.groups.delete")
            )
            return
        }

        val result = Messages.showYesNoDialog(
            project,
            SpringRsBundle.message("codegen.settings.groups.delete.confirm", activeGroupName),
            SpringRsBundle.message("codegen.settings.groups.delete"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            workingGroups.remove(activeGroupName)
            groupCombo?.removeItem(activeGroupName)
            groupCombo?.selectedIndex = 0
        }
    }

    private fun importGroupFromJson() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, mainPanel)
        val files = chooser.choose(project)
        if (files.isEmpty()) return

        val file = File(files[0].path)
        try {
            val json = file.readText()
            val gson = GsonBuilder().create()

            // Try single group format first
            val type = object : TypeToken<TemplateGroupJson>() {}.type
            val imported: TemplateGroupJson = gson.fromJson(json, type)

            val group = TemplateGroup(imported.name)
            imported.templates.forEach { (k, v) -> group.templates[k] = v }
            imported.globalVariables?.forEach { (k, v) -> group.globalVariables[k] = v }

            workingGroups[imported.name] = group
            groupCombo?.addIfAbsent(imported.name)
            groupCombo?.selectedItem = imported.name

            Messages.showInfoMessage(
                project,
                SpringRsBundle.message("codegen.settings.groups.import.success", imported.name),
                SpringRsBundle.message("codegen.settings.groups.import")
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                SpringRsBundle.message("codegen.settings.groups.import.error", e.message ?: ""),
                SpringRsBundle.message("codegen.settings.groups.import")
            )
        }
    }

    private fun exportGroupToJson() {
        saveCurrentGroupData()
        val group = workingGroups[activeGroupName] ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, mainPanel)
        val folders = chooser.choose(project)
        if (folders.isEmpty()) return

        val dir = File(folders[0].path)
        val fileName = "${activeGroupName.lowercase().replace(" ", "-")}-template-group.json"
        val file = File(dir, fileName)

        try {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val exportData = TemplateGroupJson(
                name = group.name,
                templates = group.templates.toMap(),
                globalVariables = group.globalVariables.toMap()
            )
            file.writeText(gson.toJson(exportData))

            Messages.showInfoMessage(
                project,
                SpringRsBundle.message("codegen.settings.groups.export.success", file.absolutePath),
                SpringRsBundle.message("codegen.settings.groups.export")
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                SpringRsBundle.message("codegen.settings.groups.export.error", e.message ?: ""),
                SpringRsBundle.message("codegen.settings.groups.export")
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Tab 2: Templates ──
    // ══════════════════════════════════════════════════════════════

    /**
     * Templates tab: left = template list (fixed), center = Velocity editor (scrollable),
     * right = live preview (scrollable).
     *
     * Uses [EditorFactory.createEditor] instead of [EditorTextField] for proper scrolling.
     */
    private fun createTemplatesTab(): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.border = JBUI.Borders.empty(8)

        val listPanel = createTemplateListPanel()
        val editorPanel = createTemplateEditorPanel()
        val previewPanel = createTemplatePreviewPanel()

        // Split pane: editor | preview (both scrollable independently)
        val splitter = JBSplitter(false, 0.55f).apply {
            firstComponent = editorPanel
            secondComponent = previewPanel
        }

        val hintPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel(SpringRsBundle.message("codegen.settings.template.hint")))
        }

        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(splitter, BorderLayout.CENTER)
        centerPanel.add(hintPanel, BorderLayout.SOUTH)

        panel.add(listPanel, BorderLayout.WEST)
        panel.add(centerPanel, BorderLayout.CENTER)

        // Wire selection listener
        wireTemplateListSelection()

        return panel
    }

    /** Create the left-side template name list panel. */
    private fun createTemplateListPanel(): JPanel {
        val listModel = DefaultListModel<String>()
        TemplateGroup.TEMPLATE_NAMES.forEach { listModel.addElement("$it.rs.vm") }
        templateList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(140, 0)
            minimumSize = Dimension(120, 0)
            maximumSize = Dimension(160, Int.MAX_VALUE)
            add(JBLabel(SpringRsBundle.message("codegen.settings.template.list")).apply {
                border = JBUI.Borders.emptyBottom(4)
            }, BorderLayout.NORTH)
            add(JScrollPane(templateList), BorderLayout.CENTER)
        }
    }

    /** Create a full [Editor] with standard settings for template editing or preview. */
    private fun createFullEditor(readOnly: Boolean): Editor {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("")
        return editorFactory.createEditor(document, project, PlainTextFileType.INSTANCE, readOnly).also { editor ->
            (editor as? EditorEx)?.apply {
                settings.isLineNumbersShown = true
                settings.isUseSoftWraps = false
                if (!readOnly) settings.setTabSize(4)
                settings.additionalLinesCount = 2
                setVerticalScrollbarVisible(true)
                setHorizontalScrollbarVisible(true)
            }
        }
    }

    /** Create the center Velocity template editor panel. */
    private fun createTemplateEditorPanel(): JPanel {
        templateEditorInstance = createFullEditor(readOnly = false)
        return JPanel(BorderLayout()).apply {
            add(JBLabel(SpringRsBundle.message("codegen.settings.template.editor")).apply {
                border = JBUI.Borders.emptyBottom(4)
            }, BorderLayout.NORTH)
            add(templateEditorInstance!!.component, BorderLayout.CENTER)
        }
    }

    /** Create the right-side read-only preview panel with render button. */
    private fun createTemplatePreviewPanel(): JPanel {
        previewEditorInstance = createFullEditor(readOnly = true)
        previewErrorLabel = JBLabel("").apply {
            foreground = JBColor.RED
            border = JBUI.Borders.emptyTop(4)
            isVisible = false
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        header.add(JBLabel(SpringRsBundle.message("codegen.settings.preview.title")).apply {
            border = JBUI.Borders.emptyBottom(4)
        })
        val previewBtn = JButton(SpringRsBundle.message("codegen.settings.preview.render"))
        previewBtn.addActionListener { renderPreview() }
        header.add(previewBtn)

        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(previewEditorInstance!!.component, BorderLayout.CENTER)
            add(previewErrorLabel!!, BorderLayout.SOUTH)
        }
    }

    /** Wire template list selection → load content into editor + render preview. */
    private fun wireTemplateListSelection() {
        templateList!!.addListSelectionListener {
            saveCurrentTemplate()
            val idx = templateList!!.selectedIndex
            if (idx >= 0) {
                val displayName = templateList!!.model.getElementAt(idx)
                val name = displayName.removeSuffix(".rs.vm")
                selectedTemplateName = name
                val group = workingGroups[activeGroupName]
                setEditorText(templateEditorInstance, group?.templates?.get(name) ?: "")
                renderPreview()
            }
        }

        // Select first template
        if (templateList!!.model.size > 0) {
            templateList!!.selectedIndex = 0
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Template Live Preview ──
    // ══════════════════════════════════════════════════════════════

    /**
     * Render the current template with sample data and show result in preview panel.
     */
    private fun renderPreview() {
        val templateContent = getEditorText(templateEditorInstance)
        if (templateContent.isBlank()) {
            setEditorText(previewEditorInstance, "")
            previewErrorLabel?.isVisible = false
            return
        }

        try {
            val context = SampleTableData.buildContext()
            val result = VelocityTemplateEngine.render(templateContent, context)
            setEditorText(previewEditorInstance, result)
            previewErrorLabel?.isVisible = false
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            setEditorText(previewEditorInstance, "// Render error — see below")
            previewErrorLabel?.text = "Error: $errorMsg"
            previewErrorLabel?.isVisible = true
        }
    }

    // ── Editor helpers (thread-safe document access) ──

    private fun getEditorText(editor: Editor?): String {
        return editor?.document?.text ?: ""
    }

    private fun setEditorText(editor: Editor?, text: String) {
        val doc = editor?.document ?: return
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            doc.setText(text)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Tab 3: Global Variables ──
    // ══════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════
    // ── Data Management ──
    // ══════════════════════════════════════════════════════════════

    private fun loadFromSettings() {
        val settings = CodeGenSettingsState.getInstance(project)

        workingGroups.clear()

        // Load persisted template groups
        settings.templateGroupContents.forEach { (name, templates) ->
            val group = TemplateGroup(name)
            group.templates.putAll(templates)
            settings.templateGroupVariables[name]?.let { vars ->
                group.globalVariables.putAll(vars)
            }
            workingGroups[name] = group
        }

        // Ensure "Default" group always exists
        if (!workingGroups.containsKey("Default")) {
            workingGroups["Default"] = TemplateGroup.createDefault()
        }

        // Populate group combo
        groupCombo?.removeAllItems()
        workingGroups.keys.sorted().forEach { groupCombo?.addItem(it) }

        activeGroupName = settings.activeTemplateGroup
        if (!workingGroups.containsKey(activeGroupName)) {
            activeGroupName = "Default"
        }
        groupCombo?.selectedItem = activeGroupName

        loadGroupData(activeGroupName)
    }

    private fun loadGroupData(groupName: String) {
        val group = workingGroups[groupName] ?: return

        // Reload template editor — directly load first template content
        val firstName = TemplateGroup.TEMPLATE_NAMES.firstOrNull()
        selectedTemplateName = firstName
        if (firstName != null) {
            val content = group.templates[firstName] ?: ""
            setEditorText(templateEditorInstance, content)
            // Set list selection (won't re-trigger load because selectedTemplateName is already set)
            templateList?.selectedIndex = 0
        }

        // Reload global vars
        val text = group.globalVariables.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        globalVarsEditor?.text = text
    }

    private fun saveCurrentGroupData() {
        saveCurrentTemplate()
        saveGlobalVars()
    }

    private fun saveCurrentTemplate() {
        val name = selectedTemplateName ?: return
        val content = getEditorText(templateEditorInstance)
        val group = workingGroups[activeGroupName] ?: return
        group.templates[name] = content
    }

    private fun saveGlobalVars() {
        val group = workingGroups[activeGroupName] ?: return
        val varsText = globalVarsEditor?.text ?: ""
        group.globalVariables.clear()
        varsText.lines().forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.isNotEmpty()) group.globalVariables[key] = value
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Configurable interface ──
    // ══════════════════════════════════════════════════════════════

    override fun isModified(): Boolean = true // Always allow Apply (template content comparison is expensive)

    override fun apply() {
        saveCurrentGroupData()

        val settings = CodeGenSettingsState.getInstance(project)
        settings.activeTemplateGroup = activeGroupName

        // Save all template groups
        settings.templateGroupContents.clear()
        settings.templateGroupVariables.clear()
        workingGroups.forEach { (name, group) ->
            settings.templateGroupContents[name] = group.templates.toMutableMap()
            settings.templateGroupVariables[name] = group.globalVariables.toMutableMap()
        }
    }

    override fun reset() {
        loadFromSettings()
    }

    override fun disposeUIResources() {
        // Release editors created via EditorFactory
        templateEditorInstance?.let { EditorFactory.getInstance().releaseEditor(it) }
        previewEditorInstance?.let { EditorFactory.getInstance().releaseEditor(it) }

        mainPanel = null
        templateEditorInstance = null
        previewEditorInstance = null
        templateList = null
        globalVarsEditor = null
        groupCombo = null
        previewErrorLabel = null
    }
}

/**
 * JSON structure for template group import/export.
 *
 * Format matches the roadmap specification:
 * ```json
 * {
 *   "name": "My Custom",
 *   "templates": { "entity": "...", "dto": "...", ... },
 *   "globalVariables": { "author": "...", ... }
 * }
 * ```
 */
private data class TemplateGroupJson(
    val name: String = "Default",
    val templates: Map<String, String> = emptyMap(),
    val globalVariables: Map<String, String>? = null
)
