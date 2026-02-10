package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

/**
 * Dialog for customizing columns of a single table.
 *
 * Features:
 * - Include/exclude columns
 * - Override Rust type via editable ComboBox
 * - **Add/remove virtual columns** (not in database)
 * - **Edit ext properties** per column (accessible as `$column.ext.key` in templates)
 *
 * Inspired by MyBatisCodeHelper-Pro "Column Customize" + EasyCode ColumnConfig.
 */
class ColumnCustomizeDialog(
    project: Project,
    private val tableName: String,
    private val columns: List<ColumnInfo>,
    private val override: TableOverrideConfig
) : DialogWrapper(project) {

    private val tableModel = ColumnTableModel()
    private val table = JBTable(tableModel)

    private val entityNameField = JBTextField(override.customEntityName ?: tableName.toPascalCase())

    init {
        title = SpringRsBundle.message("codegen.customize.title", tableName)
        init()

        table.setShowGrid(true)
        table.rowHeight = JBUI.scale(28)
        table.tableHeader.reorderingAllowed = false

        // Column widths
        table.columnModel.getColumn(COL_INCLUDE).apply { preferredWidth = 40; maxWidth = 50 }
        table.columnModel.getColumn(COL_NAME).preferredWidth = 120
        table.columnModel.getColumn(COL_SQL_TYPE).preferredWidth = 80
        table.columnModel.getColumn(COL_RUST_TYPE).preferredWidth = 140
        table.columnModel.getColumn(COL_FLAGS).apply { preferredWidth = 80; maxWidth = 100 }
        table.columnModel.getColumn(COL_EXT).apply { preferredWidth = 60; maxWidth = 80 }
        table.columnModel.getColumn(COL_COMMENT).preferredWidth = 140

        // Rust Type column: editable ComboBox (dropdown + manual input)
        table.columnModel.getColumn(COL_RUST_TYPE).cellEditor = RustTypeComboBoxEditor()

        // Center-align flags column
        table.columnModel.getColumn(COL_FLAGS).cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.CENTER }
        }

        // Ext column: render with link style, click to edit
        table.columnModel.getColumn(COL_EXT).cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.CENTER }
            override fun setValue(value: Any?) {
                val count = (value as? Int) ?: 0
                text = if (count > 0) "$count props" else "—"
                foreground = if (count > 0) JBColor.BLUE else JBColor.GRAY
            }
        }

        // Virtual column name: render with italic + color
        table.columnModel.getColumn(COL_NAME).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val isVirtual = tableModel.isVirtualRow(row)
                if (isVirtual && !isSelected) {
                    foreground = JBColor(0x2E7D32, 0x81C784) // green tint
                } else if (!isSelected) {
                    foreground = table.foreground
                }
                return comp
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(780, 480)
        root.border = JBUI.Borders.empty(8)

        // Top: entity name
        val topPanel = JPanel(BorderLayout(8, 0))
        topPanel.add(JBLabel(SpringRsBundle.message("codegen.field.entity.name")), BorderLayout.WEST)
        topPanel.add(entityNameField, BorderLayout.CENTER)
        root.add(topPanel, BorderLayout.NORTH)

        // Center: column table
        root.add(JScrollPane(table), BorderLayout.CENTER)

        // Bottom: action buttons
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))

        val addBtn = JButton(SpringRsBundle.message("codegen.customize.add.column"))
        addBtn.addActionListener { addVirtualColumn() }
        bottomPanel.add(addBtn)

        val removeBtn = JButton(SpringRsBundle.message("codegen.customize.remove.column"))
        removeBtn.addActionListener { removeVirtualColumn() }
        bottomPanel.add(removeBtn)

        val extBtn = JButton(SpringRsBundle.message("codegen.customize.edit.ext"))
        extBtn.addActionListener { editExtProperties() }
        bottomPanel.add(extBtn)

        bottomPanel.add(Box.createHorizontalStrut(16))
        bottomPanel.add(JBLabel(SpringRsBundle.message("codegen.customize.hint")).apply {
            foreground = JBColor.GRAY
        })

        root.add(bottomPanel, BorderLayout.SOUTH)

        return root
    }

    // ═══════════════════════════════════════════════════════
    // ── Virtual Column Actions
    // ═══════════════════════════════════════════════════════

    private fun addVirtualColumn() {
        val name = Messages.showInputDialog(
            contentPanel,
            SpringRsBundle.message("codegen.customize.add.column.prompt"),
            SpringRsBundle.message("codegen.customize.add.column"),
            Messages.getQuestionIcon()
        ) ?: return

        if (name.isBlank()) return
        if (tableModel.allRows.any { it.name == name }) {
            Messages.showWarningDialog(
                contentPanel,
                SpringRsBundle.message("codegen.customize.column.exists", name),
                SpringRsBundle.message("codegen.customize.add.column")
            )
            return
        }

        tableModel.addVirtualColumn(name)
    }

    private fun removeVirtualColumn() {
        val row = table.selectedRow
        if (row < 0) return
        if (!tableModel.isVirtualRow(row)) {
            Messages.showWarningDialog(
                contentPanel,
                SpringRsBundle.message("codegen.customize.remove.db.column"),
                SpringRsBundle.message("codegen.customize.remove.column")
            )
            return
        }
        tableModel.removeRow(row)
    }

    // ═══════════════════════════════════════════════════════
    // ── Ext Properties Editor
    // ═══════════════════════════════════════════════════════

    private fun editExtProperties() {
        val row = table.selectedRow
        if (row < 0) {
            Messages.showInfoMessage(
                contentPanel,
                SpringRsBundle.message("codegen.customize.ext.select"),
                SpringRsBundle.message("codegen.customize.edit.ext")
            )
            return
        }

        val colName = tableModel.allRows[row].name
        val currentExt = tableModel.extProperties.getOrDefault(colName, mutableMapOf())

        // Simple key=value dialog
        val text = currentExt.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        val input = Messages.showMultilineInputDialog(
            null,
            SpringRsBundle.message("codegen.customize.ext.prompt", colName),
            SpringRsBundle.message("codegen.customize.edit.ext"),
            text,
            Messages.getQuestionIcon(),
            null
        ) ?: return

        val newExt = mutableMapOf<String, String>()
        input.lines().forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.isNotEmpty()) newExt[key] = value
            }
        }
        tableModel.extProperties[colName] = newExt
        tableModel.fireTableRowsUpdated(row, row)
    }

    // ═══════════════════════════════════════════════════════
    // ── Save
    // ═══════════════════════════════════════════════════════

    override fun doOKAction() {
        // Commit any cell that is still being edited before saving
        table.cellEditor?.stopCellEditing()

        val customName = entityNameField.text.trim()
        override.customEntityName = if (customName.isEmpty() || customName == tableName.toPascalCase()) null else customName

        override.excludedColumns.clear()
        override.columnTypeOverrides.clear()
        override.columnCommentOverrides.clear()
        override.customColumns.clear()
        override.columnExtProperties.clear()

        for (i in tableModel.allRows.indices) {
            val row = tableModel.allRows[i]
            if (row.isVirtual) {
                // Save virtual column
                override.customColumns.add(CustomColumnConfig(
                    name = row.name,
                    rustType = tableModel.rustTypes[i],
                    comment = row.comment ?: ""
                ).apply {
                    isNullable = row.isNullable
                })
            } else {
                // Save DB column overrides
                if (!tableModel.included[i]) {
                    override.excludedColumns.add(row.name)
                }
                val customType = tableModel.rustTypes[i]
                if (customType != row.rustType) {
                    override.columnTypeOverrides[row.name] = customType
                }
                val customComment = tableModel.allRows[i].comment
                if (customComment != null && customComment != columns.find { it.name == row.name }?.comment) {
                    override.columnCommentOverrides[row.name] = customComment
                }
            }

            // Save ext properties
            val ext = tableModel.extProperties[row.name]
            if (!ext.isNullOrEmpty()) {
                override.columnExtProperties[row.name] = ext.toMutableMap()
            }
        }

        super.doOKAction()
    }

    // ═══════════════════════════════════════════════════════
    // ── Rust type ComboBox editor
    // ═══════════════════════════════════════════════════════

    private class RustTypeComboBoxEditor : AbstractCellEditor(), TableCellEditor {

        private val comboBox = JComboBox(COMMON_RUST_TYPES).apply {
            isEditable = true
        }

        override fun getCellEditorValue(): Any = comboBox.selectedItem?.toString() ?: "String"

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            comboBox.selectedItem = value
            return comboBox
        }

        companion object {
            private val COMMON_RUST_TYPES = arrayOf(
                "String", "i32", "i64", "i16", "i8", "u32", "u64", "u16", "u8",
                "f32", "f64", "bool",
                "DateTime", "DateTimeWithTimeZone", "Date", "Time",
                "Uuid", "Json", "Decimal", "Vec<u8>",
                "Option<String>", "Option<i32>", "Option<i64>",
                "Option<DateTime>", "Option<DateTimeWithTimeZone>",
                "Option<Uuid>", "Option<Json>", "Option<Decimal>", "Option<bool>",
            )
        }
    }

    // ═══════════════════════════════════════════════════════
    // ── Table model
    // ═══════════════════════════════════════════════════════

    companion object {
        private const val COL_INCLUDE = 0
        private const val COL_NAME = 1
        private const val COL_SQL_TYPE = 2
        private const val COL_RUST_TYPE = 3
        private const val COL_FLAGS = 4
        private const val COL_EXT = 5
        private const val COL_COMMENT = 6

        private val COLUMN_NAMES = arrayOf("✓", "Column", "SQL Type", "Rust Type", "Flags", "Ext", "Comment")
    }

    private inner class ColumnTableModel : AbstractTableModel() {

        val allRows: MutableList<ColumnInfo> = mutableListOf<ColumnInfo>().apply {
            addAll(columns.map { col ->
                val commentOverride = override.columnCommentOverrides[col.name]
                if (commentOverride != null) col.copy(comment = commentOverride) else col
            })
            addAll(override.customColumns.map { it.toColumnInfo() })
        }

        val included: MutableList<Boolean> = allRows.map { col ->
            if (col.isVirtual) true else col.name !in override.excludedColumns
        }.toMutableList()

        val rustTypes: MutableList<String> = allRows.map { col ->
            if (col.isVirtual) col.rustType else override.getEffectiveRustType(col.name, col.rustType)
        }.toMutableList()

        val extProperties: MutableMap<String, MutableMap<String, String>> = mutableMapOf<String, MutableMap<String, String>>().apply {
            override.columnExtProperties.forEach { (k, v) -> put(k, v.toMutableMap()) }
        }

        fun isVirtualRow(row: Int): Boolean = row in allRows.indices && allRows[row].isVirtual

        fun addVirtualColumn(name: String) {
            allRows.add(ColumnInfo(
                name = name, sqlType = "VIRTUAL", rustType = "String",
                isPrimaryKey = false, isNullable = false, isAutoIncrement = false,
                comment = "", defaultValue = null, isVirtual = true
            ))
            included.add(true)
            rustTypes.add("String")
            fireTableRowsInserted(allRows.size - 1, allRows.size - 1)
        }

        fun removeRow(row: Int) {
            if (row in allRows.indices && allRows[row].isVirtual) {
                extProperties.remove(allRows[row].name)
                allRows.removeAt(row)
                included.removeAt(row)
                rustTypes.removeAt(row)
                fireTableRowsDeleted(row, row)
            }
        }

        override fun getRowCount() = allRows.size
        override fun getColumnCount() = COLUMN_NAMES.size
        override fun getColumnName(column: Int) = COLUMN_NAMES[column]

        override fun getColumnClass(column: Int): Class<*> = when (column) {
            COL_INCLUDE -> java.lang.Boolean::class.java
            COL_EXT -> Integer::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = when (column) {
            COL_INCLUDE -> !isVirtualRow(row)
            COL_RUST_TYPE -> true
            COL_COMMENT -> true
            else -> false
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            if (row >= allRows.size) return null
            val col = allRows[row]
            return when (column) {
                COL_INCLUDE -> included[row]
                COL_NAME -> if (col.isVirtual) "✦ ${col.name}" else col.name
                COL_SQL_TYPE -> col.sqlType
                COL_RUST_TYPE -> rustTypes[row]
                COL_FLAGS -> buildFlags(col)
                COL_EXT -> extProperties[col.name]?.size ?: 0
                COL_COMMENT -> col.comment ?: ""
                else -> null
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (row >= allRows.size) return
            when (column) {
                COL_INCLUDE -> included[row] = value as Boolean
                COL_RUST_TYPE -> rustTypes[row] = value?.toString() ?: "String"
                COL_COMMENT -> {
                    allRows[row] = allRows[row].copy(comment = value?.toString())
                }
            }
            fireTableCellUpdated(row, column)
        }

        private fun buildFlags(col: ColumnInfo): String = buildList {
            if (col.isPrimaryKey) add("PK")
            if (col.isAutoIncrement) add("AI")
            if (col.isNullable) add("NULL")
            if (col.isVirtual) add("VIRTUAL")
        }.joinToString(", ")
    }
}
