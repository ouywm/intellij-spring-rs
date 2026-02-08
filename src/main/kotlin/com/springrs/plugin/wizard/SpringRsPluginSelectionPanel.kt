package com.springrs.plugin.wizard

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * spring-rs Plugin Selection Panel.
 *
 * Layout:
 * ```
 * ┌──────────────────────────────────────────────────────┐
 * │ spring-rs Plugins                                     │
 * │ ┌──────────────────────────────────────────────────┐ │
 * │ │ ☑ spring-web                                      │ │
 * │ │ ☐ spring-grpc                                     │ │
 * │ │ ...                                               │ │
 * │ └──────────────────────────────────────────────────┘ │
 * │ ☑ Generate code example                              │
 * │                                                      │
 * │ Extra Dependencies                                   │
 * │ [Search crates.io...                  ] [Search]     │
 * │ ┌─ Search Results ──────┬─ Added ──────────────────┐ │
 * │ │ serde 1.0.217    [→] │ serde = "1.0.217"    [×] │ │
 * │ │ tokio 1.42       [→] │ anyhow = "1.0.95"    [×] │ │
 * │ │ anyhow 1.0.95    [→] │                           │ │
 * │ └──────────────────────┴───────────────────────────┘ │
 * └──────────────────────────────────────────────────────┘
 * ```
 */
class SpringRsPluginSelectionPanel(
    private val availableItems: List<SpringRsSelectableItem>,
    private val defaultSelected: List<String>,
    private val onSelectionChanged: (() -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val checkBoxMap = mutableMapOf<String, JCheckBox>()

    private val generateExampleCheckBox = JCheckBox(SpringRsBundle.message("wizard.plugins.generate.example")).apply {
        isSelected = false
    }

    // ── Extra dependencies ──
    private val searchResultsModel = DefaultListModel<CrateSearchResult>()
    private val searchResultsList = JBList(searchResultsModel)
    private val addedDepsModel = DefaultListModel<CrateSearchResult>()
    private val addedDepsList = JBList(addedDepsModel)
    private val searchField = JBTextField()

    // (Deduplication is handled in SpringRsTemplateManager when writing Cargo.toml,
    //  not here — search results should show all crates without filtering.)

    // ── Pagination state ──
    private var currentQuery = ""
    private var currentPage = 1
    private var hasMore = false
    private var isLoading = false

    val selectedPlugins: List<String>
        get() = availableItems.filter { checkBoxMap[it.id]?.isSelected == true }.map { it.id }

    val generateExample: Boolean
        get() = generateExampleCheckBox.isSelected

    val extraDependencies: List<CrateSearchResult>
        get() = (0 until addedDepsModel.size()).map { addedDepsModel.getElementAt(it) }

    init {
        preferredSize = Dimension(700, 720)
        setupUI()
        loadDefaults()
    }

    private fun setupUI() {
        val root = JPanel(BorderLayout(0, 10))

        // ── Top: plugin list ──
        root.add(createPluginListSection(), BorderLayout.CENTER)

        // ── Bottom: extra deps ──
        root.add(createExtraDepsSection(), BorderLayout.SOUTH)

        add(root, BorderLayout.CENTER)
    }

    // ══════════════════════════════════════════════════════════════
    // ── Plugin list (flat checkbox list)
    // ══════════════════════════════════════════════════════════════

    private fun createPluginListSection(): JPanel {
        val section = JPanel(BorderLayout(0, 4))

        val titleLabel = JBLabel(SpringRsBundle.message("wizard.plugins.available")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }
        section.add(titleLabel, BorderLayout.NORTH)

        // 2-column grid: 13 plugins → 7 rows
        val cols = 2
        val rows = (availableItems.size + cols - 1) / cols
        val gridPanel = JPanel(java.awt.GridLayout(rows, cols, 4, 0)).apply {
            border = JBUI.Borders.empty(4)
        }

        for (item in availableItems) {
            val cb = JCheckBox(item.name).apply {
                isOpaque = false
                toolTipText = item.description
            }
            cb.addActionListener { onSelectionChanged?.invoke() }
            checkBoxMap[item.id] = cb
            gridPanel.add(cb)
        }

        section.add(JBScrollPane(gridPanel).apply {
            border = BorderFactory.createLineBorder(JBColor.border(), 1)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)

        // Generate example checkbox
        val bottomRow = JPanel(BorderLayout()).apply { border = JBUI.Borders.emptyTop(4) }
        bottomRow.add(generateExampleCheckBox, BorderLayout.WEST)
        section.add(bottomRow, BorderLayout.SOUTH)

        return section
    }

    // ══════════════════════════════════════════════════════════════
    // ── Extra dependencies: left results | splitter | right added
    // ══════════════════════════════════════════════════════════════

    private fun createExtraDepsSection(): JPanel {
        val section = JPanel(BorderLayout(0, 4))
        section.preferredSize = Dimension(0, 440)

        section.add(JBLabel(SpringRsBundle.message("wizard.deps.title")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }, BorderLayout.NORTH)

        // ── Search row ──
        val searchRow = JPanel(BorderLayout(6, 0))
        searchField.emptyText.text = SpringRsBundle.message("wizard.deps.search.placeholder")
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) doSearch()
            }
        })
        val searchBtn = JButton(SpringRsBundle.message("wizard.deps.search.button")).apply {
            addActionListener { doSearch() }
        }
        searchRow.add(searchField, BorderLayout.CENTER)
        searchRow.add(searchBtn, BorderLayout.EAST)

        // ── Left: search results ──
        searchResultsList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SearchResultRenderer()
            emptyText.text = SpringRsBundle.message("wizard.deps.search.placeholder")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) addSelectedResult()
                }
            })
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) addSelectedResult()
                }
            })
        }

        val searchScrollPane = JBScrollPane(searchResultsList).apply {
            border = BorderFactory.createLineBorder(JBColor.border(), 1)
        }

        // Scroll-to-bottom → auto-load next page
        searchScrollPane.verticalScrollBar.addAdjustmentListener { e ->
            val sb = e.adjustable
            if (!e.valueIsAdjusting && hasMore && !isLoading
                && sb.value + sb.visibleAmount >= sb.maximum - 20
            ) {
                loadMore()
            }
        }

        val leftPanel = JPanel(BorderLayout(0, 2))
        leftPanel.add(JBLabel(SpringRsBundle.message("wizard.deps.results.title")).apply {
            border = JBUI.Borders.emptyBottom(2)
        }, BorderLayout.NORTH)
        leftPanel.add(searchScrollPane, BorderLayout.CENTER)

        // ── Right: added dependencies ──
        addedDepsList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = AddedDepRenderer()
            emptyText.text = SpringRsBundle.message("wizard.deps.added.empty")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val idx = locationToIndex(e.point)
                    if (idx >= 0) {
                        val cellBounds = getCellBounds(idx, idx)
                        if (e.x >= cellBounds.x + cellBounds.width - 28) {
                            addedDepsModel.remove(idx)
                        }
                    }
                }
            })
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE) {
                        val idx = addedDepsList.selectedIndex
                        if (idx >= 0) addedDepsModel.remove(idx)
                    }
                }
            })
        }

        val rightPanel = JPanel(BorderLayout(0, 2))
        rightPanel.add(JBLabel(SpringRsBundle.message("wizard.deps.added.title")).apply {
            border = JBUI.Borders.emptyBottom(2)
        }, BorderLayout.NORTH)
        rightPanel.add(JBScrollPane(addedDepsList).apply {
            border = BorderFactory.createLineBorder(JBColor.border(), 1)
        }, BorderLayout.CENTER)

        // ── Splitter: left | right ──
        val splitter = JBSplitter(false, 0.5f).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
        }

        val centerPanel = JPanel(BorderLayout(0, 4))
        centerPanel.add(searchRow, BorderLayout.NORTH)
        centerPanel.add(splitter, BorderLayout.CENTER)

        section.add(centerPanel, BorderLayout.CENTER)
        return section
    }

    // ══════════════════════════════════════════════════════════════
    // ── Search & add logic
    // ══════════════════════════════════════════════════════════════

    /** New search: clear results, reset page, load first page. */
    private fun doSearch() {
        val query = searchField.text.trim()
        if (query.isEmpty()) return

        currentQuery = query
        currentPage = 1
        hasMore = false
        searchResultsModel.clear()

        fetchPage()
    }

    /** Load next page of results, appending to the existing list. */
    private fun loadMore() {
        if (!hasMore || isLoading) return
        currentPage++
        fetchPage()
    }

    /** Fetch a single page from crates.io and append filtered results. */
    private fun fetchPage() {
        isLoading = true
        searchField.isEnabled = false
        searchResultsList.emptyText.text = SpringRsBundle.message("wizard.deps.searching")

        ApplicationManager.getApplication().executeOnPooledThread {
            val page = CratesIoSearchService.search(currentQuery, currentPage)

            SwingUtilities.invokeLater {
                isLoading = false
                searchField.isEnabled = true

                if (page.error != null) {
                    searchResultsList.emptyText.text = SpringRsBundle.message("wizard.deps.search.error")
                    searchResultsList.emptyText.appendLine(page.error)
                } else if (page.crates.isEmpty() && currentPage == 1) {
                    searchResultsList.emptyText.text = SpringRsBundle.message("wizard.deps.no.results")
                } else {
                    searchResultsList.emptyText.text = SpringRsBundle.message("wizard.deps.search.placeholder")
                }

                hasMore = page.hasMore
                val filtered = deduplicateResults(page.crates)
                filtered.forEach { searchResultsModel.addElement(it) }

                if (currentPage == 1) {
                    searchField.requestFocusInWindow()
                }
            }
        }
    }

    /**
     * Only filter out crates already shown in the search list (prevents duplicates
     * when loading next page). Does NOT filter by added deps — search results
     * always show everything, dedup happens in TemplateManager when writing Cargo.toml.
     */
    private fun deduplicateResults(results: List<CrateSearchResult>): List<CrateSearchResult> {
        val existingNames = (0 until searchResultsModel.size())
            .map { searchResultsModel.getElementAt(it).name }
            .toSet()

        return results.filter { it.name !in existingNames }
    }

    /** Move the selected search result to the added list. */
    private fun addSelectedResult() {
        val selected = searchResultsList.selectedValue ?: return
        val idx = searchResultsList.selectedIndex

        addedDepsModel.addElement(selected)
        searchResultsModel.remove(idx)

        // Select next item if available
        if (searchResultsModel.size() > 0) {
            searchResultsList.selectedIndex = idx.coerceAtMost(searchResultsModel.size() - 1)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Cell renderers
    // ══════════════════════════════════════════════════════════════

    /** Renders search results: `serde 1.0.217  A serialization framework` */
    private class SearchResultRenderer : ListCellRenderer<CrateSearchResult> {
        private val component = SimpleColoredComponent()

        override fun getListCellRendererComponent(
            list: JList<out CrateSearchResult>?, value: CrateSearchResult?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            component.clear()
            component.icon = AllIcons.Nodes.PpLib
            if (value != null) {
                component.append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                component.append(" ${value.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (value.description.isNotEmpty()) {
                    val desc = if (value.description.length > 40)
                        value.description.take(37) + "..." else value.description
                    component.append("  $desc", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES)
                }
            }
            if (isSelected) {
                component.background = list?.selectionBackground
                component.foreground = list?.selectionForeground
                component.isOpaque = true
            } else {
                component.isOpaque = false
            }
            return component
        }
    }

    /** Renders added dependencies: `serde = "1.0.217"  [×]` */
    private inner class AddedDepRenderer : ListCellRenderer<CrateSearchResult> {
        private val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6)
        }
        private val nameLabel = JBLabel()
        private val removeIcon = JBLabel(AllIcons.Actions.Close).apply {
            toolTipText = SpringRsBundle.message("wizard.deps.remove.tooltip")
            border = JBUI.Borders.empty(0, 4)
        }

        override fun getListCellRendererComponent(
            list: JList<out CrateSearchResult>?, value: CrateSearchResult?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            nameLabel.text = value?.dependencyLine ?: ""
            nameLabel.icon = AllIcons.Nodes.PpLib

            panel.removeAll()
            panel.add(nameLabel, BorderLayout.CENTER)
            panel.add(removeIcon, BorderLayout.EAST)

            if (isSelected) {
                panel.background = list?.selectionBackground
                panel.isOpaque = true
            } else {
                panel.isOpaque = false
            }
            return panel
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Defaults
    // ══════════════════════════════════════════════════════════════

    private fun loadDefaults() {
        for (item in availableItems) {
            if (item.id in defaultSelected) {
                checkBoxMap[item.id]?.isSelected = true
            }
        }
    }
}
