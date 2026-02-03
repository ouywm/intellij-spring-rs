package com.springrs.plugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.icons.SpringRsIcons
import com.springrs.plugin.routes.SpringRsRouteIndex
import com.springrs.plugin.routes.SpringRsRouteUiUtil
import com.springrs.plugin.routes.SpringRsRouteUtil
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.RsFile
import org.toml.lang.psi.TomlFile
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.springrs.plugin.SpringRsBundle
import java.awt.datatransfer.StringSelection

/**
 * spring-rs Routes tool window.
 *
 * Spring-like layout:
 * - Row 1: Module dropdown | Type dropdown
 * - Row 2: Search field
 * - Body: tree list grouped by module -> folder -> file -> route
 */
class SpringRsRoutesToolWindow(private val project: Project) : com.intellij.openapi.Disposable {

    private val disposable = Disposer.newDisposable("SpringRsRoutesToolWindow")

    private val rootNode = DefaultMutableTreeNode(SpringRsBundle.message("springrs.routes.toolwindow.root"))
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private val panel = JPanel(BorderLayout())
    private val searchField = SearchTextField()

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val refreshWhenSmartScheduled = AtomicBoolean(false)
    private val REFRESH_DELAY_MS = 500

    private var selectedCrateIds: Set<String>? = null
    private var selectedType: ItemType? = ItemType.ROUTE

    private var allRoutes: List<SpringRsRouteIndex.Route> = emptyList()
    private val expandedPaths = mutableSetOf<String>()

    init {
        Disposer.register(this, disposable)

        configureTree()

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val userObject = node.userObject
                when (userObject) {
                    is RouteItem -> {
                        OpenFileDescriptor(project, userObject.route.file, userObject.route.offset).navigate(true)
                    }
                    is FileNode -> {
                        // Double-clicking a file node also opens the file.
                        OpenFileDescriptor(project, userObject.file).navigate(true)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                handlePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                handlePopup(e)
            }

            private fun handlePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val userObject = node.userObject

                // Select current node.
                tree.selectionPath = path

                if (userObject is RouteItem) {
                    showRouteContextMenu(e, userObject.route)
                }
            }
        })

        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val pathKey = getNodePathKey(node)
                if (pathKey != null) {
                    expandedPaths.add(pathKey)
                }
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val pathKey = getNodePathKey(node)
                if (pathKey != null) {
                    expandedPaths.remove(pathKey)
                }
            }
        })

        searchField.textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilters()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilters()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilters()
        })

        // === Top layout ===

        // Row 1: dropdowns (Module | Type)
        val moduleDropdown = createDropdownLabel(SpringRsBundle.message("springrs.routes.toolwindow.filter.module")) {
            showModulePopup(it)
        }
        val typeDropdown = createDropdownLabel(SpringRsBundle.message("springrs.routes.toolwindow.filter.type")) {
            showTypePopup(it)
        }

        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            border = JBUI.Borders.empty(4)
            add(moduleDropdown)
            add(typeDropdown)
        }

        // Row 2: search field
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4, 4, 4)
            add(searchField, BorderLayout.CENTER)
        }

        // Top container
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(filterPanel)
            add(searchPanel)
        }

        val scrollPane = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Store `this` in the panel client property so it can be accessed externally.
        panel.putClientProperty(TOOL_WINDOW_KEY, this)

        with(project.messageBus.connect(disposable)) {
            subscribe(
                CargoProjectsService.CARGO_PROJECTS_TOPIC,
                CargoProjectsService.CargoProjectsListener { _, _ ->
                    scheduleRefresh()
                }
            )

            // Listen to any PSI changes (including changes inside string literals and Rust structure changes).
            // This is needed to capture edits of path strings in .route("/path", ...) and function attribute changes.
            subscribe(
                com.intellij.psi.impl.PsiManagerImpl.ANY_PSI_CHANGE_TOPIC,
                object : com.intellij.psi.impl.AnyPsiChangeListener {
                    override fun afterPsiChanged(isPhysical: Boolean) {
                        scheduleRefresh()
                    }
                }
            )
        }

        // Listen to TOML config changes (e.g. global_prefix).
        com.intellij.psi.PsiManager.getInstance(project).addPsiTreeChangeListener(
            object : PsiTreeChangeAdapter() {
                override fun childrenChanged(event: PsiTreeChangeEvent) {
                    val file = event.file
                    if (file is TomlFile && SpringRsConfigFileUtil.isConfigFileName(file.name)) {
                        scheduleRefresh()
                    }
                }
            },
            disposable
        )

        refresh()
    }

    fun getContent() = panel

    /**
     * Locate a route in the tree.
     *
     * @param method HTTP method (e.g. GET, POST)
     * @param path route path (without global prefix)
     * @param filePath file path used to disambiguate same-name routes
     */
    fun locateRoute(method: String, path: String, filePath: String? = null) {
        // 1) Try locating within current filters first. If visible already, avoid resetting user filters.
        findRouteNode(rootNode, method, path, filePath)?.let { node ->
            selectAndReveal(node)
            return
        }

        // 2) Route not visible under current filters: reset filters to show all routes and try again.
        val needRebuild = selectedCrateIds != null || searchField.text.isNotEmpty()
        selectedCrateIds = null
        searchField.text = ""

        if (needRebuild) buildTree()

        findRouteNode(rootNode, method, path, filePath)?.let { node ->
            selectAndReveal(node)
        }
    }

    private fun selectAndReveal(node: DefaultMutableTreeNode) {
        val treePath = TreePath(node.path)
        tree.expandPath(treePath.parentPath)
        tree.selectionPath = treePath
        tree.scrollPathToVisible(treePath)
    }

    /**
     * Recursively find a matching route node.
     */
    private fun findRouteNode(node: DefaultMutableTreeNode, method: String, path: String, filePath: String?): DefaultMutableTreeNode? {
        val userObject = node.userObject
        if (userObject is RouteItem) {
            val route = userObject.route
            // Match method and path.
            val methodMatch = route.method.equals(method, ignoreCase = true)
            val pathMatch = route.fullPath == path || route.fullPath.endsWith(path)

            // If file path is provided, match the file as well.
            val fileMatch = filePath == null || route.file.path == filePath

            if (methodMatch && pathMatch && fileMatch) {
                return node
            }
        }

        // Recurse into child nodes.
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findRouteNode(child, method, path, filePath)
            if (found != null) return found
        }

        return null
    }

    override fun dispose() {
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = RoutesTreeCellRenderer()
        tree.rowHeight = JBUI.scale(24)
    }

    private fun applyFilters() {
        buildTree()
    }

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refresh() }, REFRESH_DELAY_MS)
    }

    private fun refresh() {
        if (DumbService.isDumb(project)) {
            allRoutes = emptyList()
            buildTree()
            scheduleRefreshWhenSmart()
            return
        }

        ReadAction.nonBlocking<List<SpringRsRouteIndex.Route>> {
            SpringRsRouteIndex.getRoutesCached(project)
        }
            .expireWith(disposable)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { routes ->
                allRoutes = routes
                buildTree()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun scheduleRefreshWhenSmart() {
        if (!refreshWhenSmartScheduled.compareAndSet(false, true)) return
        DumbService.getInstance(project).runWhenSmart {
            refreshWhenSmartScheduled.set(false)
            refresh()
        }
    }

    private fun getNodePathKey(node: DefaultMutableTreeNode): String? {
        return when (val userObject = node.userObject) {
            is ModuleNode -> "module:${userObject.moduleId}"
            is FolderNode -> "folder:${userObject.fullPath}"
            is FileNode -> "file:${userObject.file.path}"
            else -> null
        }
    }

    private fun buildTree() {
        val query = searchField.text.trim().lowercase()
        val crates = getWorkspaceCrates()
        val selected = selectedCrateIds

        val filteredRoutes = allRoutes.asSequence()
            .filter { r ->
                when (selectedType) {
                    null -> true
                    ItemType.ROUTE -> true
                }
            }
            .filter { r ->
                if (selected == null) return@filter true
                val crateId = findCrateIdForFile(crates, r.file.path) ?: return@filter false
                crateId in selected
            }
            .filter { r ->
                if (query.isEmpty()) return@filter true
                buildString {
                    append(r.method)
                    append(' ')
                    append(r.fullPath)
                    append(' ')
                    append(r.handlerName ?: "")
                    append(' ')
                    append(r.file.path)
                }.lowercase().contains(query)
            }
            .toList()

        // Group by module.
        val routesByModule = filteredRoutes.groupBy { route ->
            findCrateIdForFile(crates, route.file.path) ?: "unknown"
        }

        val modulesWithRoutes = crates.filter { it.id in routesByModule.keys }

        rootNode.removeAllChildren()

        for (crate in modulesWithRoutes) {
            val moduleRoutes = routesByModule[crate.id] ?: continue
            val moduleNode = DefaultMutableTreeNode(ModuleNode(crate.id, crate.name, moduleRoutes.size))

            // Group by file and then organize into folders.
            val routesByFile = moduleRoutes.groupBy { it.file }

            // Build folder tree structure.
            val folderTree = buildFolderTree(crate.rootPath, routesByFile)
            addFolderNodesToTree(moduleNode, folderTree, crate.rootPath)

            rootNode.add(moduleNode)
        }

        treeModel.reload()

        // Restore expanded state.
        restoreExpandedState(rootNode)
    }

    companion object {
        // Folder names to skip (common fixed directory structure).
        private val SKIP_FOLDERS = setOf("src", "target", "tests", "benches", "examples")

        /** Tool window ID as registered in `plugin.xml`. Must stay stable (do not localize). */
        const val TOOL_WINDOW_ID: String = "spring-rs Routes"

        /**
         * Key used to store SpringRsRoutesToolWindow instance in a JComponent client property.
         */
        val TOOL_WINDOW_KEY = com.intellij.openapi.util.Key.create<SpringRsRoutesToolWindow>("SpringRsRoutesToolWindow")
    }

    /**
     * Build folder tree structure.
     */
    private fun buildFolderTree(
        crateRoot: String,
        routesByFile: Map<com.intellij.openapi.vfs.VirtualFile, List<SpringRsRouteIndex.Route>>
    ): FolderTreeNode {
        val root = FolderTreeNode("", mutableMapOf(), mutableMapOf())

        for ((file, routes) in routesByFile) {
            // Compute relative path.
            val relativePath = if (file.path.startsWith(crateRoot)) {
                file.path.removePrefix(crateRoot).removePrefix("/")
            } else {
                file.path
            }

            val parts = relativePath.split("/")
            var current = root

            // Walk folder path, skipping folders listed in SKIP_FOLDERS.
            for (i in 0 until parts.size - 1) {
                val folderName = parts[i]
                // Skip fixed folders like src.
                if (folderName in SKIP_FOLDERS) {
                    continue
                }
                current = current.subFolders.getOrPut(folderName) {
                    FolderTreeNode(folderName, mutableMapOf(), mutableMapOf())
                }
            }

            // Add file.
            current.files[file] = routes
        }

        return root
    }

    /**
     * Add folder tree nodes into the JTree.
     */
    private fun addFolderNodesToTree(
        parentNode: DefaultMutableTreeNode,
        folderTree: FolderTreeNode,
        basePath: String
    ) {
        // Add subfolders first.
        for ((folderName, subFolder) in folderTree.subFolders.toSortedMap()) {
            val fullPath = if (basePath.isEmpty()) folderName else "$basePath/$folderName"
            val folderNode = DefaultMutableTreeNode(FolderNode(folderName, fullPath, countRoutesInFolder(subFolder)))
            addFolderNodesToTree(folderNode, subFolder, fullPath)
            parentNode.add(folderNode)
        }

        // Then add files.
        for ((file, routes) in folderTree.files.toSortedMap(compareBy { it.name })) {
            val fileNode = DefaultMutableTreeNode(FileNode(file, routes.size))

            // Add routes.
            for (route in routes.sortedWith(compareBy({ it.fullPath }, { it.method }))) {
                val routeNode = DefaultMutableTreeNode(RouteItem(route))
                fileNode.add(routeNode)
            }

            parentNode.add(fileNode)
        }
    }

    private fun countRoutesInFolder(folder: FolderTreeNode): Int {
        var count = folder.files.values.sumOf { it.size }
        for (subFolder in folder.subFolders.values) {
            count += countRoutesInFolder(subFolder)
        }
        return count
    }

    private fun restoreExpandedState(node: DefaultMutableTreeNode) {
        val pathKey = getNodePathKey(node)
        if (pathKey != null && pathKey in expandedPaths) {
            tree.expandPath(TreePath(node.path))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            restoreExpandedState(child)
        }
    }

    private enum class ItemType(private val messageKey: String) {
        ROUTE("springrs.routes.toolwindow.type.endpoint");

        fun displayName(): String = SpringRsBundle.message(messageKey)
    }

    private data class CrateInfo(
        val id: String,
        val name: String,
        val rootPath: String
    )

    private data class ModuleNode(
        val moduleId: String,
        val moduleName: String,
        val routeCount: Int
    )

    private data class FolderNode(
        val folderName: String,
        val fullPath: String,
        val routeCount: Int
    )

    private data class FileNode(
        val file: com.intellij.openapi.vfs.VirtualFile,
        val routeCount: Int
    )

    private data class RouteItem(
        val route: SpringRsRouteIndex.Route
    )

    private data class FolderTreeNode(
        val name: String,
        val subFolders: MutableMap<String, FolderTreeNode>,
        val files: MutableMap<com.intellij.openapi.vfs.VirtualFile, List<SpringRsRouteIndex.Route>>
    )

    private fun getWorkspaceCrates(): List<CrateInfo> {
        val cargo = project.service<CargoProjectsService>()

        return cargo.allProjects
            .asSequence()
            .flatMap { it.workspace?.packages?.asSequence() ?: emptySequence() }
            .filter { it.origin == PackageOrigin.WORKSPACE }
            .mapNotNull { pkg ->
                val root = pkg.contentRoot ?: return@mapNotNull null
                val rootPath = root.path
                CrateInfo(id = rootPath, name = pkg.name, rootPath = rootPath)
            }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun findCrateIdForFile(crates: List<CrateInfo>, filePath: String): String? {
        var best: CrateInfo? = null
        for (c in crates) {
            if (filePath == c.rootPath || filePath.startsWith(c.rootPath.trimEnd('/') + "/")) {
                if (best == null || c.rootPath.length > best.rootPath.length) best = c
            }
        }
        return best?.id
    }

    /**
     * Create a custom dropdown label: text + down-arrow icon.
     */
    private fun createDropdownLabel(text: String, onClick: (JComponent) -> Unit): JComponent {
        val label = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            val textLabel = JBLabel(text)
            add(textLabel)

            val arrowLabel = JBLabel(AllIcons.General.ArrowDown)
            add(arrowLabel)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onClick(this@apply)
                }
            })
        }
        return label
    }

    /**
     * Show module dropdown menu.
     */
    private fun showModulePopup(component: JComponent) {
        val crates = getWorkspaceCrates()
        val routesByModule = allRoutes.groupBy { route ->
            findCrateIdForFile(crates, route.file.path) ?: "unknown"
        }
        val modulesWithRoutes = crates.filter { it.id in routesByModule.keys }

        val group = DefaultActionGroup()

        // "All" option.
        group.add(object : AnAction(SpringRsBundle.message("springrs.routes.toolwindow.filter.all")) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedCrateIds = null
                applyFilters()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        // "Select..." option: shows multi-select panel with checkboxes.
        group.add(object : AnAction(SpringRsBundle.message("springrs.routes.toolwindow.filter.select")) {
            override fun actionPerformed(e: AnActionEvent) {
                showModuleSelectionPopup(component, modulesWithRoutes, routesByModule)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        group.addSeparator()

        // List modules directly (single-select shortcut).
        for (c in modulesWithRoutes) {
            val count = routesByModule[c.id]?.size ?: 0
            group.add(object : AnAction(SpringRsBundle.message("springrs.routes.toolwindow.module.item", c.name, count)) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedCrateIds = setOf(c.id)
                    applyFilters()
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            })
        }

        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(component), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)

        popup.showUnderneathOf(component)
    }

    /**
     * Show module multi-select popup (custom list keeps checkboxes always visible).
     */
    private fun showModuleSelectionPopup(
        anchorComponent: JComponent,
        modulesWithRoutes: List<CrateInfo>,
        routesByModule: Map<String, List<SpringRsRouteIndex.Route>>
    ) {
        // Data model.
        data class ModuleItem(val crate: CrateInfo, val count: Int, var selected: Boolean)

        val items = modulesWithRoutes.map { c ->
            val count = routesByModule[c.id]?.size ?: 0
            val isSelected = selectedCrateIds?.contains(c.id) == true
            ModuleItem(c, count, isSelected)
        }

        val listModel = DefaultListModel<ModuleItem>().apply {
            items.forEach { addElement(it) }
        }

        val list = com.intellij.ui.components.JBList(listModel).apply {
            cellRenderer = object : javax.swing.ListCellRenderer<ModuleItem> {
                override fun getListCellRendererComponent(
                    list: JList<out ModuleItem>,
                    value: ModuleItem,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val checkBox = JCheckBox(
                        SpringRsBundle.message("springrs.routes.toolwindow.module.item", value.crate.name, value.count)
                    ).apply {
                        this.isSelected = value.selected
                        isOpaque = true
                        background = if (isSelected) list.selectionBackground else list.background
                        foreground = if (isSelected) list.selectionForeground else list.foreground
                        border = JBUI.Borders.empty(4, 8)
                    }
                    return checkBox
                }
            }

            // Toggle selection on click.
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val idx = locationToIndex(e.point)
                    if (idx >= 0) {
                        val item = listModel.getElementAt(idx)
                        item.selected = !item.selected
                        repaint()

                        // Update filter.
                        val current = mutableSetOf<String>()
                        for (i in 0 until listModel.size()) {
                            val mi = listModel.getElementAt(i)
                            if (mi.selected) current.add(mi.crate.id)
                        }
                        selectedCrateIds = if (current.isEmpty()) null else current
                        applyFilters()
                    }
                }
            })
        }

        // Auto size based on content.
        val itemCount = modulesWithRoutes.size
        val rowHeight = JBUI.scale(32)
        val titleHeight = JBUI.scale(36)
        val listHeight = (itemCount * rowHeight).coerceIn(rowHeight, JBUI.scale(400))

        // Compute max text width.
        val fm = list.getFontMetrics(list.font)
        val maxTextWidth = modulesWithRoutes.maxOfOrNull { c ->
            val count = routesByModule[c.id]?.size ?: 0
            fm.stringWidth(SpringRsBundle.message("springrs.routes.toolwindow.module.item", c.name, count))
        } ?: JBUI.scale(100)
        // Checkbox width (~30) + text width + padding.
        val panelWidth = (JBUI.scale(30) + maxTextWidth + JBUI.scale(40)).coerceIn(JBUI.scale(200), JBUI.scale(400))

        val contentPanel = JPanel(BorderLayout()).apply {
            val titleLabel = JBLabel(SpringRsBundle.message("springrs.routes.toolwindow.filter.select.modules.title")).apply {
                border = JBUI.Borders.empty(8, 12, 4, 12)
                font = font.deriveFont(Font.BOLD)
            }
            add(titleLabel, BorderLayout.NORTH)

            val scrollPane = JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(panelWidth, listHeight)
            }
            add(scrollPane, BorderLayout.CENTER)

            preferredSize = Dimension(panelWidth, listHeight + titleHeight)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(contentPanel, list)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        popup.showUnderneathOf(anchorComponent)
    }

    /**
     * Show type dropdown menu.
     */
    private fun showTypePopup(component: JComponent) {
        val group = DefaultActionGroup()

        group.add(object : AnAction(SpringRsBundle.message("springrs.routes.toolwindow.filter.all")) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedType = null
                applyFilters()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        group.addSeparator()

        for (t in ItemType.entries) {
            group.add(object : AnAction(t.displayName()) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedType = t
                    applyFilters()
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            })
        }

        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(component), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)

        popup.showUnderneathOf(component)
    }

    /**
     * Show route context menu.
     */
    private fun showRouteContextMenu(e: MouseEvent, route: SpringRsRouteIndex.Route) {
        val group = DefaultActionGroup()

        // Copy request path (includes global prefix).
        group.add(object : AnAction(
            SpringRsBundle.message("springrs.route.context.copy.path"),
            SpringRsBundle.message("springrs.route.context.copy.path.description"),
            AllIcons.Actions.Copy
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                // route.fullPath already includes global prefix.
                CopyPasteManager.getInstance().setContents(StringSelection(route.fullPath))
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(tree),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )

        popup.show(com.intellij.ui.awt.RelativePoint(e))
    }

    /**
     * Custom tree renderer.
     *
     * Uses ColoredTreeCellRenderer to keep alignment consistent with the native tree layout.
     */
    private inner class RoutesTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return

            when (val userObject = node.userObject) {
                is ModuleNode -> {
                    icon = AllIcons.Nodes.Module
                    append(userObject.moduleName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("(${userObject.routeCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FolderNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(userObject.folderName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("(${userObject.routeCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FileNode -> {
                    icon = RsFileType.icon
                    append(userObject.file.nameWithoutExtension, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("(${userObject.routeCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is RouteItem -> {
                    val route = userObject.route
                    icon = SpringRsIcons.RequestMapping
                    append(route.fullPath, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val methodColor = SpringRsRouteUiUtil.getMethodColor(route.method)
                    append("[${route.method}]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor))

                    // Show route params.
                    val params = SpringRsRouteUtil.extractRouteParams(route.fullPath)
                    if (params.isNotEmpty()) {
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val paramsStr = SpringRsRouteUtil.formatRouteParams(params)
                        append("($paramsStr)", SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
                    }

                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(route.file.nameWithoutExtension, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
