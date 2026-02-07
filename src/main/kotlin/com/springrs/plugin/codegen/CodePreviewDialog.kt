package com.springrs.plugin.codegen

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import org.rust.lang.RsFileType
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Generated file for preview. Both path and content are mutable for user editing.
 */
data class GeneratedFile(
    var relativePath: String,
    var content: String
)

/**
 * Code preview dialog:
 * - Left: file tree (IntelliJ native [ColoredTreeCellRenderer]) with right-click rename
 * - Right: editable syntax-highlighted Rust code editor
 * - Custom split pane divider (thin line + drag handle)
 */
class CodePreviewDialog(
    private val project: Project,
    private val files: List<GeneratedFile>
) : DialogWrapper(project) {

    private val fileTree: Tree
    private val codeEditor: EditorTextField
    private val treeModel: DefaultTreeModel

    private var currentFileIndex = -1
    private val nodeToFileIndex = mutableMapOf<DefaultMutableTreeNode, Int>()

    init {
        title = SpringRsBundle.message("codegen.preview.title")
        setOKButtonText(SpringRsBundle.message("codegen.button.generate"))
        setCancelButtonText(SpringRsBundle.message("codegen.preview.close"))

        // ── File tree ──
        val rootNode = buildTreeModel()
        treeModel = DefaultTreeModel(rootNode)
        fileTree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = JBUI.scale(24)
            cellRenderer = FileTreeRenderer()
        }

        // Right-click context menu for rename
        fileTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })

        // ── Code editor (editable, Rust syntax) ──
        val document = EditorFactory.getInstance().createDocument("")
        codeEditor = object : EditorTextField(document, project, RsFileType, false, false) {
            override fun createEditor(): EditorEx = super.createEditor().also { editor ->
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                editor.settings.apply {
                    isLineNumbersShown = true
                    isWhitespacesShown = false
                    isFoldingOutlineShown = true
                    additionalLinesCount = 0
                    additionalColumnsCount = 3
                    isRightMarginShown = false
                    isUseSoftWraps = true
                    setTabSize(4)
                }
            }
        }.apply { font = JBUI.Fonts.create("Monospaced", 13) }

        init()

        expandAllNodes()
        if (files.isNotEmpty()) selectFileByIndex(0)

        // Tree selection → load file
        fileTree.addTreeSelectionListener {
            val node = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val idx = nodeToFileIndex[node] ?: return@addTreeSelectionListener
            saveCurrentEdits()
            loadFile(idx)
        }
    }

    fun getEditedFiles(): List<GeneratedFile> {
        saveCurrentEdits()
        return files
    }

    // ══════════════════════════════════════════════════════════════
    // ── Right-click rename
    // ══════════════════════════════════════════════════════════════

    private fun showContextMenu(e: MouseEvent) {
        val path = fileTree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? FileNodeData ?: return
        if (data.isDirectory) return // Only files can be renamed

        fileTree.selectionPath = path

        val menu = JPopupMenu()
        val renameItem = JMenuItem(SpringRsBundle.message("codegen.preview.rename"))
        renameItem.addActionListener { doRename(node) }
        menu.add(renameItem)
        menu.show(fileTree, e.x, e.y)
    }

    private fun doRename(node: DefaultMutableTreeNode) {
        val data = node.userObject as? FileNodeData ?: return
        val fileIndex = nodeToFileIndex[node] ?: return

        val newName = Messages.showInputDialog(
            project,
            SpringRsBundle.message("codegen.preview.rename.prompt"),
            SpringRsBundle.message("codegen.preview.rename"),
            null,
            data.name,
            null
        ) ?: return

        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == data.name) return
        val finalName = if (trimmed.endsWith(".rs")) trimmed else "$trimmed.rs"

        // Update path
        val oldPath = files[fileIndex].relativePath
        val parentDir = oldPath.substringBeforeLast("/", "")
        files[fileIndex].relativePath = if (parentDir.isEmpty()) finalName else "$parentDir/$finalName"

        // Update tree node
        node.userObject = FileNodeData(finalName, isDirectory = false)
        treeModel.nodeChanged(node)
    }

    // ══════════════════════════════════════════════════════════════
    // ── Tree model & rendering
    // ══════════════════════════════════════════════════════════════

    private data class FileNodeData(val name: String, val isDirectory: Boolean) {
        override fun toString(): String = name
    }

    private fun buildTreeModel(): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("root")
        val dirNodes = mutableMapOf<String, DefaultMutableTreeNode>()

        for ((index, file) in files.withIndex()) {
            val segments = file.relativePath.replace("\\", "/").split("/")
            var currentNode = root
            var currentPath = ""

            for ((i, segment) in segments.withIndex()) {
                currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                if (i == segments.lastIndex) {
                    val fileNode = DefaultMutableTreeNode(FileNodeData(segment, isDirectory = false))
                    currentNode.add(fileNode)
                    nodeToFileIndex[fileNode] = index
                } else {
                    currentNode = dirNodes.getOrPut(currentPath) {
                        DefaultMutableTreeNode(FileNodeData(segment, isDirectory = true)).also {
                            currentNode.add(it)
                        }
                    }
                }
            }
        }
        return root
    }

    private inner class FileTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val data = node.userObject as? FileNodeData ?: return

            if (data.isDirectory) {
                icon = AllIcons.Nodes.Folder
                append(data.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (node.childCount > 0) {
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("(${node.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            } else {
                icon = RsFileType.icon
                val base = data.name.removeSuffix(".rs")
                append(base, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (data.name.endsWith(".rs")) append(".rs", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    // ── Tree / editor helpers ──

    private fun expandAllNodes() {
        var r = 0; while (r < fileTree.rowCount) { fileTree.expandRow(r); r++ }
    }

    private fun selectFileByIndex(index: Int) {
        val node = nodeToFileIndex.entries.firstOrNull { it.value == index }?.key ?: return
        val path = TreePath(treeModel.getPathToRoot(node))
        fileTree.selectionPath = path
        fileTree.scrollPathToVisible(path)
        loadFile(index)
    }

    private fun saveCurrentEdits() {
        if (currentFileIndex in files.indices) {
            files[currentFileIndex].content = codeEditor.text
        }
    }

    private fun loadFile(index: Int) {
        currentFileIndex = index
        if (index in files.indices) codeEditor.text = files[index].content
    }

    // ══════════════════════════════════════════════════════════════
    // ── Layout
    // ══════════════════════════════════════════════════════════════

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(1000, 680)

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            dividerLocation = 270
            resizeWeight = 0.0
            border = null

            setUI(object : BasicSplitPaneUI() {
                override fun createDefaultDivider(): BasicSplitPaneDivider = object : BasicSplitPaneDivider(this) {
                    init { border = null }
                    override fun paint(g: Graphics) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = JBColor.border()
                        val lx = width / 2; g2.drawLine(lx, 0, lx, height)
                        val cy = height / 2; val r = JBUI.scale(2); val sp = JBUI.scale(6)
                        g2.color = JBColor.GRAY
                        for (i in -1..1) g2.fillOval(lx - r, cy + i * sp - r, r * 2, r * 2)
                    }
                }
            })
            dividerSize = JBUI.scale(8)

            // Left: file tree
            leftComponent = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(5)
                add(JLabel(SpringRsBundle.message("codegen.preview.files")).apply {
                    font = font.deriveFont(Font.BOLD)
                    border = JBUI.Borders.emptyBottom(5)
                }, BorderLayout.NORTH)
                add(JScrollPane(fileTree).apply { border = null }, BorderLayout.CENTER)
            }

            // Right: code editor
            rightComponent = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(5)
                add(JLabel(SpringRsBundle.message("codegen.preview.code")).apply {
                    font = font.deriveFont(Font.BOLD)
                    border = JBUI.Borders.emptyBottom(5)
                }, BorderLayout.NORTH)
                add(codeEditor, BorderLayout.CENTER)
            }
        }

        mainPanel.add(splitPane, BorderLayout.CENTER)
        return mainPanel
    }
}
