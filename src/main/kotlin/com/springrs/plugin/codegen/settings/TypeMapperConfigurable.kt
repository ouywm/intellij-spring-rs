package com.springrs.plugin.codegen.settings

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ui.AnActionButton
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.codegen.CodeGenSettingsState
import com.springrs.plugin.codegen.dialect.DatabaseType
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Add item to ComboBox only if not already present. */
private fun <T> ComboBox<T>.addIfAbsent(item: T) {
    if ((0 until itemCount).none { getItemAt(it) == item }) addItem(item)
}

/**
 * Settings page for SQL → Rust type mapping.
 *
 * Appears under: Settings → spring-rs → Code Generation → Type Mapping
 *
 * Features:
 * - Table showing columnType → rustType mappings
 * - Regex pattern support for flexible matching
 * - Group by database dialect (PostgreSQL/MySQL/SQLite)
 * - Import / Export mappings as JSON
 * - Reset to dialect defaults
 */
class TypeMapperConfigurable(private val project: Project) : Configurable {

    private var mainPanel: JPanel? = null
    private var table: JBTable? = null
    private var tableModel: TypeMappingTableModel? = null
    private var groupCombo: ComboBox<String>? = null
    private var useCustomMappingCheckBox: JCheckBox? = null

    /** Working copy of all mapping groups (saved on Apply). */
    private val workingGroups: MutableMap<String, MutableList<TypeMappingEntry>> = mutableMapOf()

    /** Currently selected group name. */
    private var currentGroup: String = "Default"

    /** Original state for isModified() comparison. */
    private var originalGroups: MutableMap<String, MutableList<TypeMappingEntry>> = mutableMapOf()
    private var originalActiveGroup: String = "Default"
    private var originalUseCustom: Boolean = false

    override fun getDisplayName(): String = SpringRsBundle.message("codegen.settings.typemapper.title")

    override fun createComponent(): JComponent {
        val settings = CodeGenSettingsState.getInstance(project)

        mainPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
        }

        // ── Top: group selector + controls ──
        val topPanel = JPanel(BorderLayout(8, 4))

        // Use custom mapping checkbox
        useCustomMappingCheckBox = JCheckBox(
            SpringRsBundle.message("codegen.settings.typemapper.use.custom"),
            settings.useCustomTypeMapping
        )
        topPanel.add(useCustomMappingCheckBox!!, BorderLayout.NORTH)

        val groupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        groupPanel.add(JBLabel(SpringRsBundle.message("codegen.settings.typemapper.group")))

        // Group ComboBox: Default + PostgreSQL/MySQL/SQLite + Custom groups
        groupCombo = ComboBox<String>().apply {
            addItem("Default")
            DatabaseType.entries.filter { it != DatabaseType.UNKNOWN }.forEach { addItem(it.displayName) }
        }
        groupPanel.add(groupCombo!!)

        // Clone group button
        val cloneBtn = JButton(SpringRsBundle.message("codegen.settings.typemapper.clone"))
        cloneBtn.addActionListener { cloneCurrentGroup() }
        groupPanel.add(cloneBtn)

        // Delete group button
        val deleteBtn = JButton(SpringRsBundle.message("codegen.settings.typemapper.delete"))
        deleteBtn.addActionListener { deleteCurrentGroup() }
        groupPanel.add(deleteBtn)

        // Import button
        val importBtn = JButton(SpringRsBundle.message("codegen.settings.typemapper.import"))
        importBtn.addActionListener { importFromFile() }
        groupPanel.add(importBtn)

        // Export button
        val exportBtn = JButton(SpringRsBundle.message("codegen.settings.typemapper.export"))
        exportBtn.addActionListener { exportToFile() }
        groupPanel.add(exportBtn)

        topPanel.add(groupPanel, BorderLayout.CENTER)
        mainPanel!!.add(topPanel, BorderLayout.NORTH)

        // ── Center: mapping table ──
        tableModel = TypeMappingTableModel()
        table = JBTable(tableModel!!).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = JBUI.scale(28)

            // Match type column uses combo box editor
            columnModel.getColumn(2).cellEditor = DefaultCellEditor(
                JComboBox(arrayOf(MatchType.REGEX.displayName, MatchType.EXACT.displayName))
            )

            // Custom renderer for match type column
            columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) {
                    text = when (value?.toString()) {
                        MatchType.REGEX.name -> MatchType.REGEX.displayName
                        MatchType.EXACT.name -> MatchType.EXACT.displayName
                        else -> value?.toString() ?: ""
                    }
                }
            }

            // Set column widths
            columnModel.getColumn(0).preferredWidth = 250
            columnModel.getColumn(1).preferredWidth = 200
            columnModel.getColumn(2).preferredWidth = 80
        }

        val decorator = ToolbarDecorator.createDecorator(table!!)
            .setAddAction { addEntry() }
            .setRemoveAction { removeEntry() }
            .addExtraAction(object : AnActionButton(
                SpringRsBundle.message("codegen.settings.typemapper.reset"),
                com.intellij.icons.AllIcons.Actions.Rollback
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    resetToDefaults()
                }
            })
            .createPanel()

        mainPanel!!.add(decorator, BorderLayout.CENTER)

        // ── Bottom: hint ──
        val hintLabel = JBLabel(SpringRsBundle.message("codegen.settings.typemapper.hint")).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(4)
        }
        mainPanel!!.add(hintLabel, BorderLayout.SOUTH)

        // ── Load data ──
        loadFromSettings()

        // ── Group change listener ──
        groupCombo!!.addActionListener {
            val selected = groupCombo!!.selectedItem as? String ?: return@addActionListener
            if (selected != currentGroup) {
                saveCurrentGroupToWorking()
                currentGroup = selected
                loadGroupIntoTable(currentGroup)
            }
        }

        return mainPanel!!
    }

    // ══════════════════════════════════════════════════════════════
    // ── Table Actions ──
    // ══════════════════════════════════════════════════════════════

    private fun addEntry() {
        tableModel?.addEntry(TypeMappingEntry("", "String", MatchType.REGEX))
        val lastRow = tableModel!!.rowCount - 1
        table?.setRowSelectionInterval(lastRow, lastRow)
        table?.editCellAt(lastRow, 0)
    }

    private fun removeEntry() {
        val row = table?.selectedRow ?: return
        if (row >= 0) {
            tableModel?.removeEntry(row)
        }
    }

    private fun resetToDefaults() {
        val result = Messages.showYesNoDialog(
            project,
            SpringRsBundle.message("codegen.settings.typemapper.reset.confirm"),
            SpringRsBundle.message("codegen.settings.typemapper.reset"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            val dbType = resolveDbType(currentGroup)
            val defaults = TypeMappingEntry.defaultsForDialect(dbType)
            workingGroups[currentGroup] = defaults.map { it.copy() }.toMutableList()
            loadGroupIntoTable(currentGroup)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Group Management ──
    // ══════════════════════════════════════════════════════════════

    private fun cloneCurrentGroup() {
        val name = Messages.showInputDialog(
            project,
            SpringRsBundle.message("codegen.settings.typemapper.clone.prompt"),
            SpringRsBundle.message("codegen.settings.typemapper.clone"),
            Messages.getQuestionIcon()
        ) ?: return

        if (name.isBlank()) return
        if (workingGroups.containsKey(name) || groupCombo?.let { combo ->
                (0 until combo.itemCount).any { combo.getItemAt(it) == name }
            } == true
        ) {
            Messages.showWarningDialog(
                project,
                SpringRsBundle.message("codegen.settings.typemapper.clone.exists", name),
                SpringRsBundle.message("codegen.settings.typemapper.clone")
            )
            return
        }

        saveCurrentGroupToWorking()
        val cloned = workingGroups[currentGroup]?.map { it.copy() }?.toMutableList() ?: mutableListOf()
        workingGroups[name] = cloned
        groupCombo?.addItem(name)
        groupCombo?.selectedItem = name
    }

    private fun deleteCurrentGroup() {
        // Cannot delete built-in groups
        val builtInNames = setOf("Default") + DatabaseType.entries
            .filter { it != DatabaseType.UNKNOWN }
            .map { it.displayName }

        if (currentGroup in builtInNames) {
            Messages.showWarningDialog(
                project,
                SpringRsBundle.message("codegen.settings.typemapper.delete.builtin"),
                SpringRsBundle.message("codegen.settings.typemapper.delete")
            )
            return
        }

        val result = Messages.showYesNoDialog(
            project,
            SpringRsBundle.message("codegen.settings.typemapper.delete.confirm", currentGroup),
            SpringRsBundle.message("codegen.settings.typemapper.delete"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            workingGroups.remove(currentGroup)
            groupCombo?.removeItem(currentGroup)
            groupCombo?.selectedIndex = 0
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Import / Export ──
    // ══════════════════════════════════════════════════════════════

    private fun importFromFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, mainPanel)
        val files = chooser.choose(project)
        if (files.isEmpty()) return

        val file = File(files[0].path)
        try {
            val json = file.readText()
            val gson = GsonBuilder().create()
            val type = object : TypeToken<Map<String, List<TypeMappingEntry>>>() {}.type
            val imported: Map<String, List<TypeMappingEntry>> = gson.fromJson(json, type)

            imported.forEach { (name, entries) ->
                workingGroups[name] = entries.map { it.copy() }.toMutableList()
                groupCombo?.addIfAbsent(name)
            }

            Messages.showInfoMessage(
                project,
                SpringRsBundle.message("codegen.settings.typemapper.import.success", imported.size),
                SpringRsBundle.message("codegen.settings.typemapper.import")
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                SpringRsBundle.message("codegen.settings.typemapper.import.error", e.message ?: ""),
                SpringRsBundle.message("codegen.settings.typemapper.import")
            )
        }
    }

    private fun exportToFile() {
        saveCurrentGroupToWorking()
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, mainPanel)
        val folders = chooser.choose(project)
        if (folders.isEmpty()) return

        val dir = File(folders[0].path)
        val file = File(dir, "spring-rs-type-mappings.json")
        try {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val dataToExport = mapOf(currentGroup to (workingGroups[currentGroup] ?: emptyList<TypeMappingEntry>()))
            file.writeText(gson.toJson(dataToExport))

            Messages.showInfoMessage(
                project,
                SpringRsBundle.message("codegen.settings.typemapper.export.success", file.absolutePath),
                SpringRsBundle.message("codegen.settings.typemapper.export")
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                SpringRsBundle.message("codegen.settings.typemapper.export.error", e.message ?: ""),
                SpringRsBundle.message("codegen.settings.typemapper.export")
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Data Loading / Saving ──
    // ══════════════════════════════════════════════════════════════

    private fun loadFromSettings() {
        val settings = CodeGenSettingsState.getInstance(project)

        // Load existing custom mappings
        workingGroups.clear()
        settings.typeMappingGroups.forEach { (name, entries) ->
            workingGroups[name] = entries.map { it.copy() }.toMutableList()
        }

        // Ensure all built-in groups have defaults if not customized
        val builtInGroups = mapOf(
            "Default" to DatabaseType.POSTGRESQL,
            DatabaseType.POSTGRESQL.displayName to DatabaseType.POSTGRESQL,
            DatabaseType.MYSQL.displayName to DatabaseType.MYSQL,
            DatabaseType.SQLITE.displayName to DatabaseType.SQLITE,
        )
        builtInGroups.forEach { (name, dbType) ->
            if (!workingGroups.containsKey(name)) {
                workingGroups[name] = TypeMappingEntry.defaultsForDialect(dbType).map { it.copy() }.toMutableList()
            }
        }

        // Add any custom groups to combo
        workingGroups.keys.forEach { name -> groupCombo?.addIfAbsent(name) }

        currentGroup = settings.activeTypeMappingGroup
        groupCombo?.selectedItem = currentGroup
        useCustomMappingCheckBox?.isSelected = settings.useCustomTypeMapping
        loadGroupIntoTable(currentGroup)

        // Save originals for isModified
        originalGroups = workingGroups.mapValues { (_, v) -> v.map { it.copy() }.toMutableList() }.toMutableMap()
        originalActiveGroup = settings.activeTypeMappingGroup
        originalUseCustom = settings.useCustomTypeMapping
    }

    private fun loadGroupIntoTable(groupName: String) {
        val entries = workingGroups[groupName]
            ?: TypeMappingEntry.defaultsForDialect(resolveDbType(groupName)).map { it.copy() }.toMutableList()
        tableModel?.setEntries(entries)
    }

    private fun saveCurrentGroupToWorking() {
        val entries = tableModel?.getEntries() ?: return
        workingGroups[currentGroup] = entries.toMutableList()
    }

    private fun resolveDbType(groupName: String): DatabaseType = when (groupName) {
        DatabaseType.POSTGRESQL.displayName -> DatabaseType.POSTGRESQL
        DatabaseType.MYSQL.displayName -> DatabaseType.MYSQL
        DatabaseType.SQLITE.displayName -> DatabaseType.SQLITE
        else -> DatabaseType.POSTGRESQL
    }

    // ══════════════════════════════════════════════════════════════
    // ── Configurable interface ──
    // ══════════════════════════════════════════════════════════════

    override fun isModified(): Boolean {
        saveCurrentGroupToWorking()
        if (useCustomMappingCheckBox?.isSelected != originalUseCustom) return true
        if (currentGroup != originalActiveGroup) return true
        if (workingGroups != originalGroups) return true
        return false
    }

    override fun apply() {
        saveCurrentGroupToWorking()
        val settings = CodeGenSettingsState.getInstance(project)

        settings.useCustomTypeMapping = useCustomMappingCheckBox?.isSelected ?: false
        settings.activeTypeMappingGroup = currentGroup
        settings.typeMappingGroups.clear()
        workingGroups.forEach { (name, entries) ->
            settings.typeMappingGroups[name] = entries.map { it.copy() }.toMutableList()
        }

        // Update originals
        originalGroups = workingGroups.mapValues { (_, v) -> v.map { it.copy() }.toMutableList() }.toMutableMap()
        originalActiveGroup = currentGroup
        originalUseCustom = useCustomMappingCheckBox?.isSelected ?: false
    }

    override fun reset() {
        loadFromSettings()
    }

    override fun disposeUIResources() {
        mainPanel = null
        table = null
        tableModel = null
        groupCombo = null
        useCustomMappingCheckBox = null
    }
}

// ══════════════════════════════════════════════════════════════
// ── Table Model ──
// ══════════════════════════════════════════════════════════════

/**
 * Table model for the type mapping table.
 *
 * Columns: columnType | rustType | matchType
 */
private class TypeMappingTableModel : AbstractTableModel() {

    private val entries: MutableList<TypeMappingEntry> = mutableListOf()

    private val columnNames = arrayOf("columnType", "rustType", "Match")

    fun setEntries(newEntries: List<TypeMappingEntry>) {
        entries.clear()
        entries.addAll(newEntries.map { it.copy() })
        fireTableDataChanged()
    }

    fun getEntries(): List<TypeMappingEntry> = entries.map { it.copy() }

    fun addEntry(entry: TypeMappingEntry) {
        entries.add(entry)
        fireTableRowsInserted(entries.size - 1, entries.size - 1)
    }

    fun removeEntry(row: Int) {
        if (row in entries.indices) {
            entries.removeAt(row)
            fireTableRowsDeleted(row, row)
        }
    }

    override fun getRowCount(): Int = entries.size

    override fun getColumnCount(): Int = 3

    override fun getColumnName(column: Int): String = columnNames[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = entries[rowIndex]
        return when (columnIndex) {
            0 -> entry.columnType
            1 -> entry.rustType
            2 -> MatchType.fromString(entry.matchType).displayName
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val entry = entries[rowIndex]
        val value = aValue?.toString() ?: ""
        when (columnIndex) {
            0 -> entry.columnType = value
            1 -> entry.rustType = value
            2 -> {
                entry.matchType = when (value) {
                    MatchType.EXACT.displayName -> MatchType.EXACT.name
                    MatchType.REGEX.displayName -> MatchType.REGEX.name
                    else -> value
                }
            }
        }
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
}
