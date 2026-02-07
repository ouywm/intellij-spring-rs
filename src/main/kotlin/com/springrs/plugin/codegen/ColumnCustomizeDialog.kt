package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

/**
 * Dialog for customizing columns of a single table.
 *
 * Rust Type column uses an **editable ComboBox** — dropdown for common types + manual input.
 * Inspired by MyBatisCodeHelper-Pro "定制列" dialog.
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
        table.columnModel.getColumn(COL_SQL_TYPE).preferredWidth = 100
        table.columnModel.getColumn(COL_RUST_TYPE).preferredWidth = 150
        table.columnModel.getColumn(COL_FLAGS).apply { preferredWidth = 80; maxWidth = 100 }
        table.columnModel.getColumn(COL_COMMENT).preferredWidth = 150

        // Rust Type column: editable ComboBox (dropdown + manual input)
        table.columnModel.getColumn(COL_RUST_TYPE).cellEditor = RustTypeComboBoxEditor()

        // Center-align flags column
        table.columnModel.getColumn(COL_FLAGS).cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.CENTER }
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(720, 420)
        root.border = JBUI.Borders.empty(8)

        // Top: entity name
        val topPanel = JPanel(BorderLayout(8, 0))
        topPanel.add(JBLabel(SpringRsBundle.message("codegen.field.entity.name")), BorderLayout.WEST)
        topPanel.add(entityNameField, BorderLayout.CENTER)
        root.add(topPanel, BorderLayout.NORTH)

        // Center: column table
        root.add(JScrollPane(table), BorderLayout.CENTER)

        return root
    }

    override fun doOKAction() {
        val customName = entityNameField.text.trim()
        override.customEntityName = if (customName.isEmpty() || customName == tableName.toPascalCase()) null else customName

        override.excludedColumns.clear()
        override.columnTypeOverrides.clear()
        for (i in columns.indices) {
            val col = columns[i]
            if (!tableModel.included[i]) {
                override.excludedColumns.add(col.name)
            }
            val customType = tableModel.rustTypes[i]
            if (customType != col.rustType) {
                override.columnTypeOverrides[col.name] = customType
            }
        }

        super.doOKAction()
    }

    // ═══════════════════════════════════════════════════════
    // ── Rust type ComboBox editor
    // ═══════════════════════════════════════════════════════

    /**
     * Editable ComboBox cell editor for Rust type selection.
     *
     * Common sea-orm types as dropdown options, but user can also type any custom type.
     */
    private class RustTypeComboBoxEditor : AbstractCellEditor(), TableCellEditor {

        private val comboBox = JComboBox(COMMON_RUST_TYPES).apply {
            isEditable = true  // Allow manual input in addition to dropdown
        }

        override fun getCellEditorValue(): Any = comboBox.selectedItem?.toString() ?: "String"

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            comboBox.selectedItem = value
            return comboBox
        }

        companion object {
            /**
             * Common Rust types for sea-orm entities.
             * Ordered by frequency of use. Aligned with sea-orm-codegen.
             */
            private val COMMON_RUST_TYPES = arrayOf(
                // ── Primitives ──
                "String",
                "i32",
                "i64",
                "i16",
                "i8",
                "u32",
                "u64",
                "u16",
                "u8",
                "f32",
                "f64",
                "bool",

                // ── Sea-ORM date/time ──
                "DateTime",
                "DateTimeWithTimeZone",
                "Date",
                "Time",

                // ── Special types ──
                "Uuid",
                "Json",
                "Decimal",
                "Vec<u8>",

                // ── Option wrappers (for nullable) ──
                "Option<String>",
                "Option<i32>",
                "Option<i64>",
                "Option<DateTime>",
                "Option<DateTimeWithTimeZone>",
                "Option<Uuid>",
                "Option<Json>",
                "Option<Decimal>",
                "Option<bool>",
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
        private const val COL_COMMENT = 5

        private val COLUMN_NAMES = arrayOf("✓", "Column", "SQL Type", "Rust Type", "Flags", "Comment")
    }

    private inner class ColumnTableModel : AbstractTableModel() {

        val included = BooleanArray(columns.size) { columns[it].name !in override.excludedColumns }

        val rustTypes = Array(columns.size) {
            override.getEffectiveRustType(columns[it].name, columns[it].rustType)
        }

        override fun getRowCount() = columns.size
        override fun getColumnCount() = COLUMN_NAMES.size
        override fun getColumnName(column: Int) = COLUMN_NAMES[column]

        override fun getColumnClass(column: Int): Class<*> = when (column) {
            COL_INCLUDE -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, column: Int) = column == COL_INCLUDE || column == COL_RUST_TYPE

        override fun getValueAt(row: Int, column: Int): Any? {
            val col = columns[row]
            return when (column) {
                COL_INCLUDE -> included[row]
                COL_NAME -> col.name
                COL_SQL_TYPE -> col.sqlType
                COL_RUST_TYPE -> rustTypes[row]
                COL_FLAGS -> buildFlags(col)
                COL_COMMENT -> col.comment ?: ""
                else -> null
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            when (column) {
                COL_INCLUDE -> included[row] = value as Boolean
                COL_RUST_TYPE -> rustTypes[row] = value?.toString() ?: "String"
            }
            fireTableCellUpdated(row, column)
        }

        private fun buildFlags(col: ColumnInfo): String = buildList {
            if (col.isPrimaryKey) add("PK")
            if (col.isAutoIncrement) add("AI")
            if (col.isNullable) add("NULL")
        }.joinToString(", ")
    }
}
