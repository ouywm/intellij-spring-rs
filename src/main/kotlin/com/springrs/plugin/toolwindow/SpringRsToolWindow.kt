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
import com.intellij.openapi.vfs.VirtualFile
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
import com.springrs.plugin.routes.*
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.RsFileType
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
 * spring-rs tool window.
 *
 * Spring-like layout:
 * - Row 1: Module dropdown | Type dropdown
 * - Row 2: Search field
 * - Body: tree list grouped by module -> folder -> file -> items
 *
 * Supports multiple item types:
 * - Endpoint (HTTP routes)
 * - Job (scheduled tasks)
 * - Component (services)
 * - Configuration (config structs)
 * - Plugin (registered plugins)
 */
class SpringRsToolWindow(private val project: Project) : com.intellij.openapi.Disposable {

    private val disposable = Disposer.newDisposable("SpringRsToolWindow")

    private val rootNode = DefaultMutableTreeNode(SpringRsBundle.message("springrs.routes.toolwindow.root"))
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private val panel = JPanel(BorderLayout())
    private val searchField = SearchTextField()

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val refreshWhenSmartScheduled = AtomicBoolean(false)
    private val REFRESH_DELAY_MS = 1000

    private var selectedCrateIds: Set<String>? = null
    private var selectedType: ItemType? = null

    private var allRoutes: List<SpringRsRouteIndex.Route> = emptyList()
    private var allJobs: List<SpringRsJobIndex.Job> = emptyList()
    private var allComponents: List<SpringRsComponentIndex.ComponentInfo> = emptyList()
    private var allStreamListeners: List<SpringRsStreamListenerIndex.StreamListener> = emptyList()
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
                    is RouteItem -> safeNavigate(userObject.route.file, userObject.route.offset)
                    is JobItem -> safeNavigate(userObject.job.file, userObject.job.offset)
                    is StreamListenerItem -> safeNavigate(userObject.listener.file, userObject.listener.offset)
                    is ComponentItem -> safeNavigate(userObject.component.file, userObject.component.offset)
                    is ConfigurationItem -> {
                        if (!navigateToConfigFile(userObject.component)) {
                            safeNavigate(userObject.component.file, userObject.component.offset)
                        }
                    }
                    is ConfigKeyValueItem -> {
                        if (userObject.entry.isFromToml) {
                            // Has TOML value → jump to config file.
                            navigateToConfigKey(userObject.configPrefix, userObject.entry.key)
                        } else {
                            // No TOML value → jump to Rust field definition.
                            safeNavigate(userObject.rustFile, userObject.entry.fieldOffset)
                        }
                    }
                    is PluginItem -> safeNavigate(userObject.component.file, userObject.component.offset)
                    is FileNode -> safeNavigate(userObject.file, -1)
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

                when (userObject) {
                    is RouteItem -> showRouteContextMenu(e, userObject.route)
                    is JobItem -> showJobContextMenu(e, userObject.job)
                    is ConfigurationItem -> showConfigContextMenu(e, userObject.component)
                    is ConfigKeyValueItem -> {
                        if (userObject.entry.isFromToml) {
                            showConfigKeyContextMenu(e, userObject.configPrefix, userObject.entry)
                        }
                    }
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

            // Listen to any PSI changes.
            subscribe(
                com.intellij.psi.impl.PsiManagerImpl.ANY_PSI_CHANGE_TOPIC,
                object : com.intellij.psi.impl.AnyPsiChangeListener {
                    override fun afterPsiChanged(isPhysical: Boolean) {
                        scheduleRefresh()
                    }
                }
            )
        }

        // Listen to TOML config changes.
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
     */
    fun locateRoute(method: String, path: String, filePath: String? = null) {
        findLeafNode(rootNode) { userObject ->
            if (userObject !is RouteItem) return@findLeafNode false
            val route = userObject.route
            val methodMatch = route.method.equals(method, ignoreCase = true)
            val pathMatch = route.fullPath == path || route.fullPath.endsWith(path)
            val fileMatch = filePath == null || route.file.path == filePath
            methodMatch && pathMatch && fileMatch
        }?.let { node ->
            selectAndReveal(node)
            return
        }

        // Reset filters and try again.
        val needRebuild = selectedCrateIds != null || searchField.text.isNotEmpty()
        selectedCrateIds = null
        searchField.text = ""
        if (needRebuild) buildTree()

        findLeafNode(rootNode) { userObject ->
            if (userObject !is RouteItem) return@findLeafNode false
            val route = userObject.route
            val methodMatch = route.method.equals(method, ignoreCase = true)
            val pathMatch = route.fullPath == path || route.fullPath.endsWith(path)
            val fileMatch = filePath == null || route.file.path == filePath
            methodMatch && pathMatch && fileMatch
        }?.let { node ->
            selectAndReveal(node)
        }
    }

    /**
     * Locate a job in the tree.
     */
    fun locateJob(handlerName: String, jobType: String, filePath: String? = null) {
        findLeafNode(rootNode) { userObject ->
            if (userObject !is JobItem) return@findLeafNode false
            val job = userObject.job
            val nameMatch = job.handlerName == handlerName
            val typeMatch = job.type.displayName == jobType
            val fileMatch = filePath == null || job.file.path == filePath
            nameMatch && typeMatch && fileMatch
        }?.let { node ->
            selectAndReveal(node)
            return
        }

        // Reset filters and try again.
        val needRebuild = selectedCrateIds != null || searchField.text.isNotEmpty() || selectedType != null
        selectedCrateIds = null
        selectedType = null
        searchField.text = ""
        if (needRebuild) buildTree()

        findLeafNode(rootNode) { userObject ->
            if (userObject !is JobItem) return@findLeafNode false
            val job = userObject.job
            val nameMatch = job.handlerName == handlerName
            val typeMatch = job.type.displayName == jobType
            val fileMatch = filePath == null || job.file.path == filePath
            nameMatch && typeMatch && fileMatch
        }?.let { node ->
            selectAndReveal(node)
        }
    }

    /**
     * Locate a stream listener in the tree.
     */
    fun locateStreamListener(handlerName: String, topic: String, filePath: String? = null) {
        findLeafNode(rootNode) { userObject ->
            if (userObject !is StreamListenerItem) return@findLeafNode false
            val sl = userObject.listener
            val nameMatch = sl.handlerName == handlerName
            val fileMatch = filePath == null || sl.file.path == filePath
            nameMatch && fileMatch
        }?.let { node ->
            selectAndReveal(node)
            return
        }

        // Reset filters and try again.
        val needRebuild = selectedCrateIds != null || searchField.text.isNotEmpty() || selectedType != null
        selectedCrateIds = null
        selectedType = null
        searchField.text = ""
        if (needRebuild) buildTree()

        findLeafNode(rootNode) { userObject ->
            if (userObject !is StreamListenerItem) return@findLeafNode false
            val sl = userObject.listener
            val nameMatch = sl.handlerName == handlerName
            val fileMatch = filePath == null || sl.file.path == filePath
            nameMatch && fileMatch
        }?.let { node ->
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
     * Generic leaf node finder.
     */
    private fun findLeafNode(node: DefaultMutableTreeNode, predicate: (Any) -> Boolean): DefaultMutableTreeNode? {
        val userObject = node.userObject
        if (predicate(userObject)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findLeafNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    override fun dispose() {
    }

    /**
     * Safely navigate to a file at the given offset.
     * Uses invokeLater to avoid EDT deadlocks during mouse click handlers.
     */
    private fun safeNavigate(file: VirtualFile, offset: Int) {
        if (!file.isValid) return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (offset >= 0) {
                OpenFileDescriptor(project, file, offset).navigate(true)
            } else {
                OpenFileDescriptor(project, file).navigate(true)
            }
        }
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ToolWindowTreeCellRenderer()
        tree.rowHeight = JBUI.scale(24)
    }

    private fun applyFilters() {
        buildTree()
    }

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refresh() }, REFRESH_DELAY_MS)
    }

    /**
     * Loaded data holder for background read.
     */
    private data class AllData(
        val routes: List<SpringRsRouteIndex.Route>,
        val jobs: List<SpringRsJobIndex.Job>,
        val components: List<SpringRsComponentIndex.ComponentInfo>,
        val streamListeners: List<SpringRsStreamListenerIndex.StreamListener>
    )

    private fun refresh() {
        if (DumbService.isDumb(project)) {
            allRoutes = emptyList()
            allJobs = emptyList()
            allComponents = emptyList()
            allStreamListeners = emptyList()
            buildTree()
            scheduleRefreshWhenSmart()
            return
        }

        ReadAction.nonBlocking<AllData> {
            AllData(
                routes = SpringRsRouteIndex.getRoutesCached(project),
                jobs = SpringRsJobIndex.getJobsCached(project),
                components = SpringRsComponentIndex.getComponentsCached(project),
                streamListeners = SpringRsStreamListenerIndex.getListenersCached(project)
            )
        }
            .expireWith(disposable)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { data ->
                allRoutes = data.routes
                allJobs = data.jobs
                allComponents = data.components
                allStreamListeners = data.streamListeners
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

    // ==================== Unified Tree Item ====================

    /**
     * A unified wrapper for any item that can appear in the tree.
     * Each item has a file and offset for grouping and navigation.
     */
    private sealed class TreeLeafItem {
        abstract val file: VirtualFile
        abstract val offset: Int

        /** Produces a search string for filtering. */
        abstract fun searchText(): String
    }

    private class RouteLeaf(val route: SpringRsRouteIndex.Route) : TreeLeafItem() {
        override val file get() = route.file
        override val offset get() = route.offset
        override fun searchText() = "${route.method} ${route.fullPath} ${route.handlerName ?: ""} ${route.file.path}"
    }

    private class JobLeaf(val job: SpringRsJobIndex.Job) : TreeLeafItem() {
        override val file get() = job.file
        override val offset get() = job.offset
        override fun searchText() = "${job.type.displayName} ${job.expression} ${job.handlerName ?: ""} ${job.file.path}"
    }

    private class ComponentLeaf(val component: SpringRsComponentIndex.ComponentInfo) : TreeLeafItem() {
        override val file get() = component.file
        override val offset get() = component.offset
        override fun searchText() = "${component.type.displayName} ${component.name} ${component.detail ?: ""} ${component.file.path}"
    }

    private class StreamListenerLeaf(val listener: SpringRsStreamListenerIndex.StreamListener) : TreeLeafItem() {
        override val file get() = listener.file
        override val offset get() = listener.offset
        override fun searchText() = "stream ${listener.topicsDisplay()} ${listener.handlerName ?: ""} ${listener.groupId ?: ""} ${listener.file.path}"
    }

    // ==================== Build Tree ====================

    private fun buildTree() {
        val query = searchField.text.trim().lowercase()
        val crates = getWorkspaceCrates()
        val selected = selectedCrateIds

        // Collect filtered items based on selected type.
        val items = mutableListOf<TreeLeafItem>()

        // Routes.
        if (selectedType == null || selectedType == ItemType.ROUTE) {
            for (r in allRoutes) {
                items.add(RouteLeaf(r))
            }
        }

        // Jobs.
        if (selectedType == null || selectedType == ItemType.JOB) {
            for (j in allJobs) {
                items.add(JobLeaf(j))
            }
        }

        // Stream Listeners.
        if (selectedType == null || selectedType == ItemType.STREAM) {
            for (sl in allStreamListeners) {
                items.add(StreamListenerLeaf(sl))
            }
        }

        // Components (Service, Configuration, Plugin).
        if (selectedType == null || selectedType == ItemType.COMPONENT || selectedType == ItemType.CONFIGURATION || selectedType == ItemType.PLUGIN) {
            for (c in allComponents) {
                val match = when (selectedType) {
                    ItemType.COMPONENT -> c.type == SpringRsComponentIndex.ComponentType.SERVICE
                    ItemType.CONFIGURATION -> c.type == SpringRsComponentIndex.ComponentType.CONFIGURATION
                    ItemType.PLUGIN -> c.type == SpringRsComponentIndex.ComponentType.PLUGIN
                    else -> true // null = All
                }
                if (match) {
                    items.add(ComponentLeaf(c))
                }
            }
        }

        // Apply module filter.
        val filteredByModule = if (selected == null) items else {
            items.filter { item ->
                val crateId = findCrateIdForFile(crates, item.file.path)
                crateId != null && crateId in selected
            }
        }

        // Apply search filter.
        val filteredItems = if (query.isEmpty()) filteredByModule else {
            filteredByModule.filter { it.searchText().lowercase().contains(query) }
        }

        // Group by module.
        val itemsByModule = filteredItems.groupBy { item ->
            findCrateIdForFile(crates, item.file.path) ?: "unknown"
        }

        val modulesWithItems = crates.filter { it.id in itemsByModule.keys }

        rootNode.removeAllChildren()

        // Single-crate optimization: skip Module layer when there's only one crate.
        val isSingleCrate = modulesWithItems.size == 1

        for (crate in modulesWithItems) {
            val moduleItems = itemsByModule[crate.id] ?: continue

            // Group by file.
            val itemsByFile = moduleItems.groupBy { it.file }

            // Build folder tree structure.
            val folderTree = buildFolderTree(crate.rootPath, itemsByFile)

            if (isSingleCrate) {
                // Single project: add folders/files directly under root, no Module node.
                addFolderNodesToTree(rootNode, folderTree, crate.rootPath)
            } else {
                // Workspace project: wrap in Module node.
                val moduleNode = DefaultMutableTreeNode(ModuleNode(crate.id, crate.name, moduleItems.size))
                addFolderNodesToTree(moduleNode, folderTree, crate.rootPath)
                rootNode.add(moduleNode)
            }
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
        val TOOL_WINDOW_KEY = com.intellij.openapi.util.Key.create<SpringRsToolWindow>("SpringRsToolWindow")
    }

    /**
     * Build folder tree structure.
     */
    private fun buildFolderTree(
        crateRoot: String,
        itemsByFile: Map<VirtualFile, List<TreeLeafItem>>
    ): FolderTreeNode {
        val root = FolderTreeNode("", mutableMapOf(), mutableMapOf())

        for ((file, fileItems) in itemsByFile) {
            val relativePath = if (file.path.startsWith(crateRoot)) {
                file.path.removePrefix(crateRoot).removePrefix("/")
            } else {
                file.path
            }

            val parts = relativePath.split("/")
            var current = root

            for (i in 0 until parts.size - 1) {
                val folderName = parts[i]
                if (folderName in SKIP_FOLDERS) continue
                current = current.subFolders.getOrPut(folderName) {
                    FolderTreeNode(folderName, mutableMapOf(), mutableMapOf())
                }
            }

            current.files[file] = fileItems
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
            val folderNode = DefaultMutableTreeNode(FolderNode(folderName, fullPath, countItemsInFolder(subFolder)))
            addFolderNodesToTree(folderNode, subFolder, fullPath)
            parentNode.add(folderNode)
        }

        // Then add files.
        for ((file, fileItems) in folderTree.files.toSortedMap(compareBy { it.name })) {
            val fileNode = DefaultMutableTreeNode(FileNode(file, fileItems.size))

            // Add leaf items.
            for (item in fileItems) {
                val leafNode = when (item) {
                    is RouteLeaf -> DefaultMutableTreeNode(RouteItem(item.route))
                    is JobLeaf -> DefaultMutableTreeNode(JobItem(item.job))
                    is StreamListenerLeaf -> DefaultMutableTreeNode(StreamListenerItem(item.listener))
                    is ComponentLeaf -> when (item.component.type) {
                        SpringRsComponentIndex.ComponentType.SERVICE ->
                            DefaultMutableTreeNode(ComponentItem(item.component))
                        SpringRsComponentIndex.ComponentType.CONFIGURATION -> {
                            // Configuration is a tree: parent node + child key-value nodes.
                            val configNode = DefaultMutableTreeNode(ConfigurationItem(item.component))
                            val configPrefix = item.component.detail ?: ""
                            val rustFile = item.component.file
                            item.component.configEntries?.forEach { entry ->
                                configNode.add(DefaultMutableTreeNode(ConfigKeyValueItem(entry, configPrefix, rustFile)))
                            }
                            configNode
                        }
                        SpringRsComponentIndex.ComponentType.PLUGIN ->
                            DefaultMutableTreeNode(PluginItem(item.component))
                    }
                }
                fileNode.add(leafNode)
            }

            parentNode.add(fileNode)
        }
    }

    private fun countItemsInFolder(folder: FolderTreeNode): Int {
        var count = folder.files.values.sumOf { it.size }
        for (subFolder in folder.subFolders.values) {
            count += countItemsInFolder(subFolder)
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

    // ==================== Navigation Helpers ====================

    /**
     * Navigate to the TOML config file for a configuration component.
     * Returns true if navigation succeeded.
     */
    private fun navigateToConfigFile(component: SpringRsComponentIndex.ComponentInfo): Boolean {
        val configPrefix = component.detail ?: return false

        // Find TOML config files and look for the section.
        val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)

        for (vFile in com.intellij.psi.search.FileTypeIndex.getFiles(org.toml.lang.psi.TomlFileType, scope)) {
            if (!SpringRsConfigFileUtil.isConfigFileName(vFile.name)) continue

            val tomlFile = psiManager.findFile(vFile) as? TomlFile ?: continue
            for (child in tomlFile.children) {
                if (child is org.toml.lang.psi.TomlTable) {
                    val sectionName = child.header.key?.segments?.joinToString(".") { it.name ?: "" }
                    if (sectionName == configPrefix) {
                        OpenFileDescriptor(project, vFile, child.textOffset).navigate(true)
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Navigate to a specific key within a TOML config section.
     */
    private fun navigateToConfigKey(configPrefix: String, key: String): Boolean {
        val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)

        for (vFile in com.intellij.psi.search.FileTypeIndex.getFiles(org.toml.lang.psi.TomlFileType, scope)) {
            if (!SpringRsConfigFileUtil.isConfigFileName(vFile.name)) continue

            val tomlFile = psiManager.findFile(vFile) as? TomlFile ?: continue
            for (child in tomlFile.children) {
                if (child is org.toml.lang.psi.TomlTable) {
                    val sectionName = child.header.key?.segments?.joinToString(".") { it.name ?: "" }
                    if (sectionName == configPrefix) {
                        // Find the specific key in this section.
                        for (entry in child.entries) {
                            if (entry.key.text == key) {
                                OpenFileDescriptor(project, vFile, entry.textOffset).navigate(true)
                                return true
                            }
                        }
                        // Key not found in TOML, navigate to the section header.
                        OpenFileDescriptor(project, vFile, child.textOffset).navigate(true)
                        return true
                    }
                }
            }
        }

        return false
    }

    // ==================== Data Types ====================

    private enum class ItemType(private val messageKey: String) {
        ROUTE("springrs.routes.toolwindow.type.endpoint"),
        JOB("springrs.routes.toolwindow.type.job"),
        STREAM("springrs.routes.toolwindow.type.stream"),
        COMPONENT("springrs.routes.toolwindow.type.component"),
        CONFIGURATION("springrs.routes.toolwindow.type.configuration"),
        PLUGIN("springrs.routes.toolwindow.type.plugin");

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
        val itemCount: Int
    )

    private data class FolderNode(
        val folderName: String,
        val fullPath: String,
        val itemCount: Int
    )

    private data class FileNode(
        val file: VirtualFile,
        val itemCount: Int
    )

    private data class RouteItem(
        val route: SpringRsRouteIndex.Route
    )

    private data class JobItem(
        val job: SpringRsJobIndex.Job
    )

    private data class ComponentItem(
        val component: SpringRsComponentIndex.ComponentInfo
    )

    private data class ConfigurationItem(
        val component: SpringRsComponentIndex.ComponentInfo
    )

    /** Child node under ConfigurationItem showing a single key=value entry. */
    private data class ConfigKeyValueItem(
        val entry: SpringRsComponentIndex.ConfigEntry,
        val configPrefix: String,
        val rustFile: VirtualFile  // The Rust source file where the field is defined
    )

    private data class StreamListenerItem(
        val listener: SpringRsStreamListenerIndex.StreamListener
    )

    private data class PluginItem(
        val component: SpringRsComponentIndex.ComponentInfo
    )

    private data class FolderTreeNode(
        val name: String,
        val subFolders: MutableMap<String, FolderTreeNode>,
        val files: MutableMap<VirtualFile, List<TreeLeafItem>>
    )

    // ==================== Workspace Crates ====================

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

    // ==================== Dropdown UI ====================

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

        // Count all items per module (not just routes).
        val allItems = mutableListOf<TreeLeafItem>()
        allRoutes.forEach { allItems.add(RouteLeaf(it)) }
        allJobs.forEach { allItems.add(JobLeaf(it)) }
        allStreamListeners.forEach { allItems.add(StreamListenerLeaf(it)) }
        allComponents.forEach { allItems.add(ComponentLeaf(it)) }

        val itemsByModule = allItems.groupBy { item ->
            findCrateIdForFile(crates, item.file.path) ?: "unknown"
        }
        val modulesWithItems = crates.filter { it.id in itemsByModule.keys }

        val group = DefaultActionGroup()

        // "All" option.
        group.add(object : AnAction(SpringRsBundle.message("springrs.routes.toolwindow.filter.all")) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedCrateIds = null
                applyFilters()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        // "Select..." option.
        group.add(object : AnAction(SpringRsBundle.message("springrs.routes.toolwindow.filter.select")) {
            override fun actionPerformed(e: AnActionEvent) {
                showModuleSelectionPopup(component, modulesWithItems, itemsByModule)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        group.addSeparator()

        for (c in modulesWithItems) {
            val count = itemsByModule[c.id]?.size ?: 0
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
     * Show module multi-select popup.
     */
    private fun showModuleSelectionPopup(
        anchorComponent: JComponent,
        modulesWithItems: List<CrateInfo>,
        itemsByModule: Map<String, List<TreeLeafItem>>
    ) {
        data class ModuleItem(val crate: CrateInfo, val count: Int, var selected: Boolean)

        val items = modulesWithItems.map { c ->
            val count = itemsByModule[c.id]?.size ?: 0
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

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val idx = locationToIndex(e.point)
                    if (idx >= 0) {
                        val item = listModel.getElementAt(idx)
                        item.selected = !item.selected
                        repaint()

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

        val itemCount = modulesWithItems.size
        val rowHeight = JBUI.scale(32)
        val titleHeight = JBUI.scale(36)
        val listHeight = (itemCount * rowHeight).coerceIn(rowHeight, JBUI.scale(400))

        val fm = list.getFontMetrics(list.font)
        val maxTextWidth = modulesWithItems.maxOfOrNull { c ->
            val count = itemsByModule[c.id]?.size ?: 0
            fm.stringWidth(SpringRsBundle.message("springrs.routes.toolwindow.module.item", c.name, count))
        } ?: JBUI.scale(100)
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

    // ==================== Context Menus ====================

    private fun showRouteContextMenu(e: MouseEvent, route: SpringRsRouteIndex.Route) {
        val group = DefaultActionGroup()

        group.add(object : AnAction(
            SpringRsBundle.message("springrs.route.context.copy.path"),
            SpringRsBundle.message("springrs.route.context.copy.path.description"),
            AllIcons.Actions.Copy
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(route.fullPath))
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(tree), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)

        popup.show(com.intellij.ui.awt.RelativePoint(e))
    }

    private fun showJobContextMenu(e: MouseEvent, job: SpringRsJobIndex.Job) {
        val group = DefaultActionGroup()

        // Copy cron expression / delay value.
        group.add(object : AnAction(
            SpringRsBundle.message("springrs.job.context.copy.expression"),
            SpringRsBundle.message("springrs.job.context.copy.expression.description"),
            AllIcons.Actions.Copy
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(job.expression))
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(tree), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)

        popup.show(com.intellij.ui.awt.RelativePoint(e))
    }

    private fun showConfigContextMenu(e: MouseEvent, component: SpringRsComponentIndex.ComponentInfo) {
        val group = DefaultActionGroup()

        // Navigate to config file.
        group.add(object : AnAction(
            SpringRsBundle.message("springrs.config.context.goto.toml"),
            SpringRsBundle.message("springrs.config.context.goto.toml.description"),
            AllIcons.FileTypes.Config
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                navigateToConfigFile(component)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        // Copy config prefix.
        if (component.detail != null) {
            group.add(object : AnAction(
                SpringRsBundle.message("springrs.config.context.copy.prefix"),
                SpringRsBundle.message("springrs.config.context.copy.prefix.description"),
                AllIcons.Actions.Copy
            ) {
                override fun actionPerformed(event: AnActionEvent) {
                    CopyPasteManager.getInstance().setContents(StringSelection(component.detail))
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            })
        }

        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(tree), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)

        popup.show(com.intellij.ui.awt.RelativePoint(e))
    }

    private fun showConfigKeyContextMenu(e: MouseEvent, configPrefix: String, entry: SpringRsComponentIndex.ConfigEntry) {
        val group = DefaultActionGroup()

        // Navigate to this key in TOML.
        group.add(object : AnAction(
            SpringRsBundle.message("springrs.config.context.goto.toml"),
            SpringRsBundle.message("springrs.config.context.goto.toml.description"),
            AllIcons.FileTypes.Config
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                navigateToConfigKey(configPrefix, entry.key)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })

        // Copy value.
        val displayVal = entry.displayValue()
        if (displayVal != null) {
            group.add(object : AnAction(
                SpringRsBundle.message("springrs.config.context.copy.value"),
                SpringRsBundle.message("springrs.config.context.copy.value.description"),
                AllIcons.Actions.Copy
            ) {
                override fun actionPerformed(event: AnActionEvent) {
                    CopyPasteManager.getInstance().setContents(StringSelection(displayVal))
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            })
        }

        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(tree), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)

        popup.show(com.intellij.ui.awt.RelativePoint(e))
    }


    // ==================== Tree Renderer ====================

    /**
     * Custom tree renderer supporting all item types.
     */
    private inner class ToolWindowTreeCellRenderer : ColoredTreeCellRenderer() {
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
                    append("(${userObject.itemCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FolderNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(userObject.folderName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("(${userObject.itemCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FileNode -> {
                    icon = RsFileType.icon
                    append(userObject.file.nameWithoutExtension, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("(${userObject.itemCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is RouteItem -> renderRoute(userObject)
                is JobItem -> renderJob(userObject)
                is StreamListenerItem -> renderStreamListener(userObject)
                is ComponentItem -> renderComponent(userObject)
                is ConfigurationItem -> renderConfiguration(userObject)
                is ConfigKeyValueItem -> renderConfigKeyValue(userObject)
                is PluginItem -> renderPlugin(userObject)
            }
        }

        private fun renderRoute(item: RouteItem) {
            val route = item.route
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

        private fun renderJob(item: JobItem) {
            val job = item.job
            icon = SpringRsIcons.Job
            append(job.handlerName ?: "unknown", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            val typeColor = SpringRsRouteUiUtil.getJobTypeColor(job.type.displayName)
            append("[${job.type.displayName}]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, typeColor))
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(job.expression, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        private fun renderStreamListener(item: StreamListenerItem) {
            val sl = item.listener
            icon = SpringRsIcons.StreamListener
            append(sl.handlerName ?: "unknown", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("[${sl.topicsDisplay()}]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.CYAN))

            // Show extra info.
            sl.consumerMode?.let {
                append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            sl.groupId?.let {
                append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("group=$it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            sl.optionsType?.let {
                append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("($it)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }

        private fun renderComponent(item: ComponentItem) {
            val comp = item.component
            icon = SpringRsIcons.SpringRsBean
            append(comp.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            val typeColor = SpringRsRouteUiUtil.getComponentTypeColor("SERVICE")
            append("[Service]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, typeColor))
        }

        private fun renderConfiguration(item: ConfigurationItem) {
            val comp = item.component
            icon = SpringRsIcons.SpringRsTomlConfig
            val prefix = comp.detail ?: "?"
            append("[$prefix]", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(comp.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Show entry count.
            val entryCount = comp.configEntries?.size ?: 0
            if (entryCount > 0) {
                append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("($entryCount)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        private fun renderConfigKeyValue(item: ConfigKeyValueItem) {
            val entry = item.entry
            icon = AllIcons.Nodes.Property
            append(entry.key, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(" = ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val displayVal = entry.displayValue()
            if (displayVal != null) {
                if (entry.isFromToml) {
                    // Value from TOML config file.
                    append(displayVal, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                } else {
                    // Default value from Rust struct.
                    append(displayVal, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(SpringRsBundle.message("springrs.completion.tail.default"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            } else {
                append("?", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }

        private fun renderPlugin(item: PluginItem) {
            val comp = item.component
            icon = AllIcons.Nodes.Plugin
            append(comp.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            val typeColor = SpringRsRouteUiUtil.getComponentTypeColor("PLUGIN")
            append("[Plugin]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, typeColor))
        }
    }
}
