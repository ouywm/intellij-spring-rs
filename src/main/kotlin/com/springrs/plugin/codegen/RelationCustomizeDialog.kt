package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
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
 * Dialog for defining logical foreign key relations.
 *
 * Bidirectional view: shows ALL FKs involving the current table,
 * both forward (this table holds the FK) and reverse (other tables reference this table).
 *
 * 4-column table using standard database FK notation:
 *   fk_table(fk_column) → ref_table(ref_column)
 *
 * Editing from either table's dialog syncs the same underlying data.
 */
class RelationCustomizeDialog(
    project: Project,
    private val tableName: String,
    private val allTableNames: List<String>,
    private val allTableColumns: Map<String, List<ColumnInfo>>,
    private val allRelations: Map<String, List<CustomRelationConfig>>
) : DialogWrapper(project) {

    private val tableModel = RelationTableModel()
    private val table = JBTable(tableModel)

    init {
        title = SpringRsBundle.message("codegen.relation.title", tableName)
        init()

        table.setShowGrid(true)
        table.rowHeight = JBUI.scale(28)
        table.tableHeader.reorderingAllowed = false

        // Column widths
        table.columnModel.getColumn(COL_FK_TABLE).preferredWidth = 160
        table.columnModel.getColumn(COL_FK_COL).preferredWidth = 160
        table.columnModel.getColumn(COL_REF_TABLE).preferredWidth = 160
        table.columnModel.getColumn(COL_REF_COL).preferredWidth = 160

        // FK Table: editable ComboBox with all table names
        table.columnModel.getColumn(COL_FK_TABLE).cellEditor = TableNameEditor()

        // FK Column: dynamic ComboBox based on FK Table
        table.columnModel.getColumn(COL_FK_COL).cellEditor = DynamicColumnEditor(COL_FK_TABLE)

        // References Table: editable ComboBox with all table names
        table.columnModel.getColumn(COL_REF_TABLE).cellEditor = TableNameEditor()

        // References Column: dynamic ComboBox based on References Table
        table.columnModel.getColumn(COL_REF_COL).cellEditor = DynamicColumnEditor(COL_REF_TABLE)

        // Gray out current table name in FK Table / Ref Table columns
        val grayRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected && value?.toString() == tableName) {
                    foreground = JBColor.GRAY
                }
                return comp
            }
        }
        table.columnModel.getColumn(COL_FK_TABLE).cellRenderer = grayRenderer
        table.columnModel.getColumn(COL_REF_TABLE).cellRenderer = grayRenderer
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(780, 440)
        root.border = JBUI.Borders.empty(8)

        root.add(JScrollPane(table), BorderLayout.CENTER)

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        buttonRow.add(JButton(SpringRsBundle.message("codegen.relation.add")).apply {
            addActionListener { addRelation() }
        })
        buttonRow.add(JButton(SpringRsBundle.message("codegen.relation.remove")).apply {
            addActionListener { removeRelation() }
        })
        root.add(buttonRow, BorderLayout.SOUTH)

        return root
    }

    private fun addRelation() {
        tableModel.addRow()
        val newRow = tableModel.rowCount - 1
        table.setRowSelectionInterval(newRow, newRow)
    }

    private fun removeRelation() {
        val row = table.selectedRow
        if (row >= 0) {
            table.cellEditor?.stopCellEditing()
            tableModel.removeRow(row)
        }
    }

    // ═══════════════════════════════════════════════════════
    // ── Result: per-table custom relations
    // ═══════════════════════════════════════════════════════

    override fun doOKAction() {
        // Commit any cell that is still being edited before saving
        table.cellEditor?.stopCellEditing()
        super.doOKAction()
    }

    /**
     * Return all FK definitions grouped by FK owner table.
     * Each FK is stored as BELONGS_TO on the table that holds the FK column.
     */
    fun getAllRelations(): Map<String, List<CustomRelationConfig>> {
        return tableModel.toConfigsByTable()
    }

    companion object {
        private const val COL_FK_TABLE = 0
        private const val COL_FK_COL = 1
        private const val COL_REF_TABLE = 2
        private const val COL_REF_COL = 3

        private val COLUMN_NAMES by lazy {
            arrayOf(
                SpringRsBundle.message("codegen.relation.col.fk.table"),
                SpringRsBundle.message("codegen.relation.col.fk.column"),
                SpringRsBundle.message("codegen.relation.col.ref.table"),
                SpringRsBundle.message("codegen.relation.col.ref.column")
            )
        }
    }

    // ═══════════════════════════════════════════════════════
    // ── Cell Editors
    // ═══════════════════════════════════════════════════════

    /** Editable ComboBox with all table names. */
    private inner class TableNameEditor : AbstractCellEditor(), TableCellEditor {
        private val comboBox = JComboBox(allTableNames.toTypedArray()).apply { isEditable = true }

        override fun getCellEditorValue(): Any = comboBox.selectedItem?.toString() ?: ""
        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            comboBox.selectedItem = value
            return comboBox
        }
    }

    /**
     * Dynamic ComboBox populated with columns of the table specified
     * in [sourceTableColumn] of the same row.
     */
    private inner class DynamicColumnEditor(
        private val sourceTableColumn: Int
    ) : AbstractCellEditor(), TableCellEditor {
        private val comboBox = JComboBox<String>().apply { isEditable = true }

        override fun getCellEditorValue(): Any = comboBox.selectedItem?.toString() ?: ""
        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            comboBox.removeAllItems()
            val tblName = tableModel.getValueAt(row, sourceTableColumn)?.toString() ?: ""
            val cols = allTableColumns[tblName]
            if (cols != null) {
                for (col in cols) comboBox.addItem(col.name)
            }
            comboBox.selectedItem = value ?: ""
            return comboBox
        }
    }

    // ═══════════════════════════════════════════════════════
    // ── Table Model
    // ═══════════════════════════════════════════════════════

    private inner class RelationTableModel : AbstractTableModel() {

        // Each row: [fkTable, fkColumn, refTable, refColumn]
        val rows: MutableList<MutableList<String>> = buildInitialRows()

        /**
         * Collect all FKs involving the current table:
         * - Forward: this table's own customRelations (fkTable = tableName)
         * - Reverse: other tables' customRelations where targetTable == tableName
         */
        private fun buildInitialRows(): MutableList<MutableList<String>> {
            val result = mutableListOf<MutableList<String>>()
            for ((ownerTable, rels) in allRelations) {
                for (rel in rels) {
                    val isForward = ownerTable.equals(tableName, ignoreCase = true)
                    val isReverse = rel.targetTable.equals(tableName, ignoreCase = true)
                    if (isForward || isReverse) {
                        result.add(mutableListOf(ownerTable, rel.fromColumn, rel.targetTable, rel.toColumn))
                    }
                }
            }
            return result
        }

        fun addRow() {
            rows.add(mutableListOf(tableName, "", "", ""))
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        fun removeRow(row: Int) {
            if (row in rows.indices) {
                rows.removeAt(row)
                fireTableRowsDeleted(row, row)
            }
        }

        /**
         * Group results by FK owner table (the table that holds the FK column).
         * Each entry is stored as BELONGS_TO on that owner table.
         */
        fun toConfigsByTable(): Map<String, List<CustomRelationConfig>> {
            return rows
                .filter { it[COL_FK_TABLE].isNotBlank() && it[COL_FK_COL].isNotBlank()
                        && it[COL_REF_TABLE].isNotBlank() && it[COL_REF_COL].isNotBlank() }
                .groupBy { it[COL_FK_TABLE] }
                .mapValues { (_, tableRows) ->
                    tableRows.map { CustomRelationConfig("BELONGS_TO", it[COL_REF_TABLE], it[COL_FK_COL], it[COL_REF_COL]) }
                }
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMN_NAMES.size
        override fun getColumnName(column: Int) = COLUMN_NAMES[column]
        override fun isCellEditable(row: Int, column: Int) = true

        override fun getValueAt(row: Int, column: Int): Any? {
            if (row >= rows.size) return null
            return rows[row][column]
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (row >= rows.size) return
            rows[row][column] = value?.toString() ?: ""
            fireTableCellUpdated(row, column)
        }
    }
}