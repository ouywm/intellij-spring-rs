package com.springrs.plugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import org.rust.lang.RsFileType
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

/**
 * JSON to Rust struct dialog.
 *
 * Two-pane layout:
 * - left: JSON input (syntax highlighted) + history
 * - right: Rust output preview (syntax highlighted, editable)
 * - bottom: option checkboxes + Format/OK/Cancel buttons
 */
class JsonToStructDialog(private val project: Project) : DialogWrapper(project) {

    private val jsonEditor: EditorTextField
    private val rustEditor: EditorTextField

    // Option checkboxes.
    private val serdeCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.serde"), true)
    private val debugCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.debug"), true)
    private val cloneCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.clone"), false)
    private val renameCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.rename"), false)
    private val optionCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.option"), true)
    private val publicStructCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.public.struct"), true)
    private val publicFieldCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.public.field"), true)
    private val valueCommentsCheckBox = JBCheckBox(SpringRsBundle.message("json.to.struct.option.value.comments"), false)
    private val insertAtFileEndCheckBox =
        JBCheckBox(SpringRsBundle.message("json.to.struct.option.insert.at.file.end"), false)

    init {
        // Create syntax-highlighted editors.
        jsonEditor = createEditor(getJsonFileType(), "")
        rustEditor = createEditor(RsFileType, "")

        title = SpringRsBundle.message("json.to.struct.dialog.title")
        init()

        // Listen for JSON edits and update output live.
        jsonEditor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                updateRustOutput()
            }
        }, this.disposable)

        // Listen for option changes.
        val optionListener = { _: Any -> updateRustOutput() }
        serdeCheckBox.addActionListener(optionListener)
        debugCheckBox.addActionListener(optionListener)
        cloneCheckBox.addActionListener(optionListener)
        renameCheckBox.addActionListener(optionListener)
        optionCheckBox.addActionListener(optionListener)
        publicStructCheckBox.addActionListener(optionListener)
        publicFieldCheckBox.addActionListener(optionListener)
        valueCommentsCheckBox.addActionListener(optionListener)
    }

    private fun createEditor(fileType: com.intellij.openapi.fileTypes.FileType, initialText: String): EditorTextField {
        val document = EditorFactory.getInstance().createDocument(initialText)
        return object : EditorTextField(document, project, fileType, false, false) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                editor.settings.apply {
                    isLineNumbersShown = true
                    isWhitespacesShown = false
                    isFoldingOutlineShown = true
                    isAutoCodeFoldingEnabled = true
                    additionalLinesCount = 0
                    additionalColumnsCount = 3
                    isRightMarginShown = false
                    setTabSize(2)
                    isUseSoftWraps = true
                }
                return editor
            }
        }.apply {
            preferredSize = Dimension(400, 400)
            font = JBUI.Fonts.create("Monospaced", 13)
        }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(950, 650)

        // === Top: split pane ===
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            dividerLocation = 450
            resizeWeight = 0.5
            border = null

            // Custom divider painting (thinner/cleaner).
            setUI(object : BasicSplitPaneUI() {
                override fun createDefaultDivider(): BasicSplitPaneDivider {
                    return object : BasicSplitPaneDivider(this) {
                        init {
                            border = null
                        }

                        override fun paint(g: Graphics) {
                            val g2 = g as Graphics2D
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                            // Paint divider line.
                            g2.color = JBColor.border()
                            val lineX = width / 2
                            g2.drawLine(lineX, 0, lineX, height)

                            // Paint drag handle (three dots).
                            val centerY = height / 2
                            val dotRadius = JBUI.scale(2)
                            val dotSpacing = JBUI.scale(6)
                            g2.color = JBColor.GRAY

                            for (i in -1..1) {
                                val y = centerY + i * dotSpacing
                                g2.fillOval(lineX - dotRadius, y - dotRadius, dotRadius * 2, dotRadius * 2)
                            }
                        }
                    }
                }
            })
            dividerSize = JBUI.scale(8)

            // Left: JSON input (with history button).
            leftComponent = createJsonPanelWithHistory()

            // Right: Rust output.
            rightComponent = createPanelWithTitle(
                SpringRsBundle.message("json.to.struct.rust.output"),
                rustEditor
            )
        }

        mainPanel.add(splitPane, BorderLayout.CENTER)

        // === Bottom: options area (two rows) ===
        val optionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 5, 0, 5)

            // Row 1: derive options + rename + Option + value comments.
            val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
            row1.add(serdeCheckBox)
            row1.add(debugCheckBox)
            row1.add(cloneCheckBox)
            row1.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 20) })
            row1.add(renameCheckBox)
            row1.add(optionCheckBox)
            row1.add(valueCommentsCheckBox)
            add(row1)

            // Row 2: visibility options.
            val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
            row2.add(publicStructCheckBox)
            row2.add(publicFieldCheckBox)
            row2.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 20) })
            row2.add(insertAtFileEndCheckBox)
            add(row2)
        }

        mainPanel.add(optionsPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    /**
     * Create a JSON panel with a history button.
     */
    private fun createJsonPanelWithHistory(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)

            // Header: title + history button.
            val titleBar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(5)

                val titleLabel = JBLabel(SpringRsBundle.message("json.to.struct.json.input")).apply {
                    font = font.deriveFont(Font.BOLD)
                }
                add(titleLabel, BorderLayout.WEST)

                // History button.
                val historyButton = JBLabel(AllIcons.Vcs.History)
                historyButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                historyButton.toolTipText = SpringRsBundle.message("json.to.struct.history.tooltip")
                historyButton.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        showHistoryPopup(historyButton, e)
                    }
                })
                add(historyButton, BorderLayout.EAST)
            }

            add(titleBar, BorderLayout.NORTH)
            add(jsonEditor, BorderLayout.CENTER)
        }
    }

    /**
     * Show history popup.
     */
    private fun showHistoryPopup(component: JComponent, e: MouseEvent) {
        val historyService = JsonToStructHistoryService.getInstance()
        val historyItems = historyService.getHistory()

        if (historyItems.isEmpty()) {
            // No history items.
            JBPopupFactory.getInstance()
                .createMessage(SpringRsBundle.message("json.to.struct.history.empty"))
                .show(RelativePoint(component, Point(e.x, e.y + 10)))
            return
        }

        // Build history list.
        val listModel = DefaultListModel<JsonToStructHistoryService.HistoryItem>()
        historyItems.forEach { listModel.addElement(it) }

        val list = JList(listModel).apply {
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val item = value as? JsonToStructHistoryService.HistoryItem
                    text = item?.preview ?: ""
                    border = JBUI.Borders.empty(4, 8)
                    return this
                }
            }
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        val scrollPane = JScrollPane(list).apply {
            preferredSize = Dimension(400, 200)
            border = null
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, list)
            .setTitle(SpringRsBundle.message("json.to.struct.history.title"))
            .setMovable(false)
            .setResizable(true)
            .setRequestFocus(true)
            .createPopup()

        // Double-click to pick a history item.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = list.selectedValue
                    if (selected != null) {
                        jsonEditor.text = selected.json
                        updateRustOutput()
                        popup.cancel()
                    }
                }
            }
        })

        popup.showUnderneathOf(component)
    }

    override fun createLeftSideActions(): Array<Action> {
        // Place Format button on the left.
        val formatAction = object : DialogWrapperAction(SpringRsBundle.message("json.to.struct.button.format")) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                formatJson()
            }
        }
        return arrayOf(formatAction)
    }

    private fun createPanelWithTitle(title: String, content: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)

            val titleLabel = JBLabel(title).apply {
                border = JBUI.Borders.emptyBottom(5)
                font = font.deriveFont(Font.BOLD)
            }
            add(titleLabel, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    private fun formatJson() {
        val json = jsonEditor.text
        val formatted = JsonToStructConverter.formatJson(json)
        jsonEditor.text = formatted
    }

    private fun updateRustOutput() {
        val json = jsonEditor.text
        val options = getCurrentOptions()
        val rustCode = JsonToStructConverter.convert(json, "Root", options)
        rustEditor.text = rustCode
    }

    /**
     * Get current option values.
     */
    fun getCurrentOptions(): JsonToStructConverter.ConvertOptions {
        return JsonToStructConverter.ConvertOptions(
            serdeDerive = serdeCheckBox.isSelected,
            debugDerive = debugCheckBox.isSelected,
            cloneDerive = cloneCheckBox.isSelected,
            addRenameMacro = renameCheckBox.isSelected,
            addOptionT = optionCheckBox.isSelected,
            publicStruct = publicStructCheckBox.isSelected,
            publicField = publicFieldCheckBox.isSelected,
            addValueComments = valueCommentsCheckBox.isSelected
        )
    }

    /**
     * Get final Rust code (user may have edited the right pane).
     */
    fun getRustCode(): String {
        return rustEditor.text
    }

    /**
     * Get JSON content (used for saving history).
     */
    fun getJsonContent(): String {
        return jsonEditor.text
    }

    /**
     * Get required `use` statements.
     */
    fun getRequiredImports(): List<String> {
        return JsonToStructConverter.getRequiredImports(getCurrentOptions(), getRustCode())
    }

    fun isInsertAtFileEnd(): Boolean = insertAtFileEndCheckBox.isSelected

    /**
     * Set initial JSON content.
     */
    fun setJsonContent(json: String) {
        jsonEditor.text = json
        updateRustOutput()
    }

    /**
     * Get JSON file type with fallback.
     *
     * Uses reflection to load JsonFileType if available (JSON module is optional).
     * Falls back to PlainTextFileType if JSON module is not available.
     */
    private fun getJsonFileType(): FileType {
        return try {
            val clazz = Class.forName("com.intellij.json.JsonFileType")
            val field = clazz.getDeclaredField("INSTANCE")
            field.get(null) as FileType
        } catch (e: Exception) {
            PlainTextFileType.INSTANCE
        }
    }
}
