package com.springrs.plugin.wizard

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * spring-rs Plugin Selection Panel.
 *
 * Left: Checkbox list of all plugins
 * Right: Selected plugins with remove button
 */
class SpringRsPluginSelectionPanel(
    private val availableItems: List<SpringRsSelectableItem>,
    private val defaultSelected: List<String>,
    private val onSelectionChanged: (() -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val checkBoxMap = mutableMapOf<String, JCheckBox>()
    private val selectedListModel = DefaultListModel<SpringRsSelectableItem>()
    private val selectedList = JBList(selectedListModel)
    private val generateExampleCheckBox = JCheckBox(SpringRsBundle.message("wizard.plugins.generate.example")).apply {
        isSelected = true
    }

    val selectedPlugins: List<String>
        get() = (0 until selectedListModel.size()).map { selectedListModel.getElementAt(it).id }

    val generateExample: Boolean
        get() = generateExampleCheckBox.isSelected

    init {
        preferredSize = Dimension(700, 290)
        setupUI()
        loadDefaults()
    }

    private fun setupUI() {
        val mainPanel = JPanel(BorderLayout(15, 0))

        // Left panel - Checkbox list
        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(280, 200)
        }

        // Title label (no TitledBorder, just a label)
        val leftTitleLabel = JBLabel(SpringRsBundle.message("wizard.plugins.available")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            border = JBUI.Borders.emptyBottom(5)
        }
        leftPanel.add(leftTitleLabel, BorderLayout.NORTH)

        val checkBoxPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        availableItems.forEach { item ->
            val itemPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 28)
                border = JBUI.Borders.empty(2, 0)
            }

            val checkBox = JCheckBox().apply {
                isOpaque = false
            }

            val label = JBLabel(item.name).apply {
                border = JBUI.Borders.emptyLeft(5)
                toolTipText = item.description
            }

            // Click on label also toggles checkbox
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    checkBox.isSelected = !checkBox.isSelected
                    if (checkBox.isSelected) addToSelected(item) else removeFromSelected(item)
                }
            })

            checkBox.addActionListener {
                if (checkBox.isSelected) addToSelected(item) else removeFromSelected(item)
            }

            itemPanel.add(checkBox, BorderLayout.WEST)
            itemPanel.add(label, BorderLayout.CENTER)

            checkBoxMap[item.id] = checkBox
            checkBoxPanel.add(itemPanel)
        }

        leftPanel.add(JBScrollPane(checkBoxPanel).apply {
            border = BorderFactory.createLineBorder(JBColor.border(), 1)
        }, BorderLayout.CENTER)

        // Right panel - Selected list only (no description)
        val rightPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(380, 200)
        }

        // Title label
        val rightTitleLabel = JBLabel(SpringRsBundle.message("wizard.plugins.selected")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            border = JBUI.Borders.emptyBottom(5)
        }
        rightPanel.add(rightTitleLabel, BorderLayout.NORTH)

        selectedList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SelectedItemRenderer()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val cellBounds = getCellBounds(index, index)
                        // Click on X button (right 28px area)
                        if (e.x >= cellBounds.x + cellBounds.width - 28) {
                            removeFromSelected(selectedListModel.getElementAt(index))
                        }
                    }
                }
            })
        }
        rightPanel.add(JBScrollPane(selectedList).apply {
            border = BorderFactory.createLineBorder(JBColor.border(), 1)
        }, BorderLayout.CENTER)

        mainPanel.add(leftPanel, BorderLayout.WEST)
        mainPanel.add(rightPanel, BorderLayout.CENTER)

        add(mainPanel, BorderLayout.CENTER)

        // Bottom: Generate example checkbox
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(10)
        }
        bottomPanel.add(generateExampleCheckBox, BorderLayout.WEST)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun loadDefaults() {
        availableItems.filter { it.id in defaultSelected }.forEach { item ->
            checkBoxMap[item.id]?.isSelected = true
            selectedListModel.addElement(item)
        }
    }

    private fun addToSelected(item: SpringRsSelectableItem) {
        val exists = (0 until selectedListModel.size()).any {
            selectedListModel.getElementAt(it).id == item.id
        }
        if (!exists) {
            selectedListModel.addElement(item)
            onSelectionChanged?.invoke()
        }
    }

    private fun removeFromSelected(item: SpringRsSelectableItem) {
        selectedListModel.removeElement(item)
        checkBoxMap[item.id]?.isSelected = false
        onSelectionChanged?.invoke()
    }

    private inner class SelectedItemRenderer : ListCellRenderer<SpringRsSelectableItem> {
        private val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
        }
        private val nameLabel = JBLabel()
        private val removeButton = JBLabel(AllIcons.Actions.Close).apply {
            border = JBUI.Borders.empty(0, 5)
            toolTipText = SpringRsBundle.message("wizard.plugins.remove.tooltip")
        }

        override fun getListCellRendererComponent(
            list: JList<out SpringRsSelectableItem>?,
            value: SpringRsSelectableItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            nameLabel.text = value?.name ?: ""
            nameLabel.icon = AllIcons.Nodes.Plugin

            panel.removeAll()
            panel.add(nameLabel, BorderLayout.CENTER)
            panel.add(removeButton, BorderLayout.EAST)

            if (isSelected) {
                panel.background = list?.selectionBackground ?: JBColor.BLUE
                nameLabel.foreground = list?.selectionForeground ?: JBColor.WHITE
                panel.isOpaque = true
            } else {
                panel.background = list?.background ?: JBColor.WHITE
                nameLabel.foreground = list?.foreground ?: JBColor.BLACK
                panel.isOpaque = false
            }

            return panel
        }
    }
}
