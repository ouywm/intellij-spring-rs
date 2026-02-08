package com.springrs.plugin.search

import com.intellij.ide.actions.searcheverywhere.*
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Processor
import com.springrs.plugin.icons.SpringRsIcons
import com.springrs.plugin.routes.*
import javax.swing.*

/**
 * Search Everywhere contributor for spring-rs items.
 *
 * Adds routes, jobs, components, stream listeners to Search Everywhere (double-Shift).
 * Items appear in the "All" tab with spring-rs icons.
 */
class SpringRsSearchEverywhereContributor(private val event: AnActionEvent) :
    WeightedSearchEverywhereContributor<SpringRsSearchItem> {

    private val project: Project? = event.project

    override fun getSearchProviderId(): String = "SpringRsSearchEverywhere"
    override fun getGroupName(): String = "spring-rs"
    override fun getSortWeight(): Int = 500
    override fun showInFindResults(): Boolean = false
    override fun isShownInSeparateTab(): Boolean = false

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<SpringRsSearchItem>>
    ) {
        val proj = project ?: return
        if (DumbService.isDumb(proj)) return
        if (pattern.length < 2) return // Don't search with very short patterns.

        val lowerPattern = pattern.lowercase()

        ReadAction.run<Throwable> {
            // Routes
            SpringRsRouteIndex.getRoutesCached(proj).forEach { route ->
                progressIndicator.checkCanceled()
                val text = "${route.method} ${route.fullPath}"
                if (text.lowercase().contains(lowerPattern) || (route.handlerName?.lowercase()?.contains(lowerPattern) == true)) {
                    val item = SpringRsSearchItem(
                        name = text,
                        detail = route.handlerName ?: "",
                        icon = SpringRsIcons.RequestMapping,
                        file = route.file,
                        offset = route.offset,
                        type = "Endpoint",
                        location = SpringRsSearchItem.computeLocation(route.file, route.offset, proj)
                    )
                    consumer.process(FoundItemDescriptor(item, 100))
                }
            }

            // Jobs
            SpringRsJobIndex.getJobsCached(proj).forEach { job ->
                progressIndicator.checkCanceled()
                val name = job.handlerName ?: return@forEach
                if (name.lowercase().contains(lowerPattern) || job.expression.lowercase().contains(lowerPattern)) {
                    val item = SpringRsSearchItem(
                        name = "$name [${job.type.displayName}]",
                        detail = job.expression,
                        icon = SpringRsIcons.Job,
                        file = job.file,
                        offset = job.offset,
                        type = "Job",
                        location = SpringRsSearchItem.computeLocation(job.file, job.offset, proj)
                    )
                    consumer.process(FoundItemDescriptor(item, 80))
                }
            }

            // Stream Listeners
            SpringRsStreamListenerIndex.getListenersCached(proj).forEach { sl ->
                progressIndicator.checkCanceled()
                val name = sl.handlerName ?: return@forEach
                if (name.lowercase().contains(lowerPattern) || sl.topicsDisplay().lowercase().contains(lowerPattern)) {
                    val item = SpringRsSearchItem(
                        name = "$name [${sl.topicsDisplay()}]",
                        detail = "Stream Listener",
                        icon = SpringRsIcons.StreamListener,
                        file = sl.file,
                        offset = sl.offset,
                        type = "Stream",
                        location = SpringRsSearchItem.computeLocation(sl.file, sl.offset, proj)
                    )
                    consumer.process(FoundItemDescriptor(item, 70))
                }
            }

            // Components (Service, Configuration, Plugin)
            SpringRsComponentIndex.getComponentsCached(proj).forEach { comp ->
                progressIndicator.checkCanceled()
                if (comp.name.lowercase().contains(lowerPattern) || (comp.detail?.lowercase()?.contains(lowerPattern) == true)) {
                    val icon = when (comp.type) {
                        SpringRsComponentIndex.ComponentType.SERVICE -> SpringRsIcons.SpringRsBean
                        SpringRsComponentIndex.ComponentType.CONFIGURATION -> SpringRsIcons.SpringRsTomlConfig
                        SpringRsComponentIndex.ComponentType.PLUGIN -> SpringRsIcons.SpringLeaf
                    }
                    val item = SpringRsSearchItem(
                        name = comp.name,
                        detail = comp.detail ?: comp.type.displayName,
                        icon = icon,
                        file = comp.file,
                        offset = comp.offset,
                        type = comp.type.displayName,
                        location = SpringRsSearchItem.computeLocation(comp.file, comp.offset, proj)
                    )
                    consumer.process(FoundItemDescriptor(item, 60))
                }
            }
        }
    }

    override fun processSelectedItem(selected: SpringRsSearchItem, modifiers: Int, searchText: String): Boolean {
        val proj = project ?: return false
        if (selected.file.isValid) {
            OpenFileDescriptor(proj, selected.file, selected.offset).navigate(true)
            return true
        }
        return false
    }

    override fun getDataForItem(element: SpringRsSearchItem, dataId: String): Any? = null

    override fun getElementsRenderer(): ListCellRenderer<in SpringRsSearchItem> {
        return SpringRsSearchItemRenderer()
    }

    /**
     * Factory that registers this contributor in Search Everywhere.
     */
    class Factory : SearchEverywhereContributorFactory<SpringRsSearchItem> {
        override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<SpringRsSearchItem> {
            return SpringRsSearchEverywhereContributor(initEvent)
        }
    }
}

/**
 * A search result item.
 *
 * [location] is pre-computed (e.g. "src/web/mod.rs:42") inside ReadAction
 * so that the renderer can use it safely on the EDT.
 */
data class SpringRsSearchItem(
    val name: String,
    val detail: String,
    val icon: Icon,
    val file: VirtualFile,
    val offset: Int,
    val type: String,
    val location: String = ""
) {
    companion object {
        /**
         * Compute a display location string like "src/web/mod.rs:42".
         * MUST be called inside ReadAction.
         */
        fun computeLocation(file: VirtualFile, offset: Int, project: Project): String {
            // Relative path
            val relPath = run {
                val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
                if (contentRoot != null) {
                    VfsUtilCore.getRelativePath(file, contentRoot) ?: file.name
                } else {
                    file.name
                }
            }
            // Line number
            val doc = FileDocumentManager.getInstance().getDocument(file)
            if (doc != null) {
                val safeOffset = offset.coerceIn(0, (doc.textLength - 1).coerceAtLeast(0))
                val line = doc.getLineNumber(safeOffset) + 1
                return "$relPath:$line"
            }
            return relPath
        }
    }
}

/**
 * Renderer for search items in the Search Everywhere popup.
 *
 * Uses a single-row BoxLayout so all labels share the same vertical baseline.
 * Layout:  [icon] name  detail (type)  ──stretch──  file/path.rs:42
 */
private class SpringRsSearchItemRenderer : ListCellRenderer<SpringRsSearchItem> {
    private val panel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = javax.swing.border.EmptyBorder(2, 4, 2, 4)
    }
    private val nameLabel = JLabel().apply {
        alignmentY = java.awt.Component.CENTER_ALIGNMENT
    }
    private val detailLabel = JLabel().apply {
        foreground = java.awt.Color.GRAY
        alignmentY = java.awt.Component.CENTER_ALIGNMENT
    }
    private val locationLabel = JLabel().apply {
        foreground = java.awt.Color.GRAY
        alignmentY = java.awt.Component.CENTER_ALIGNMENT
    }

    override fun getListCellRendererComponent(
        list: JList<out SpringRsSearchItem>,
        value: SpringRsSearchItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        nameLabel.icon = value.icon
        nameLabel.text = value.name
        detailLabel.text = "  ${value.detail}  (${value.type})"
        locationLabel.text = value.location

        panel.removeAll()
        panel.add(nameLabel)
        panel.add(detailLabel)
        panel.add(Box.createHorizontalGlue())  // push location to the right
        panel.add(locationLabel)

        panel.background = if (isSelected) list.selectionBackground else list.background
        nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

        return panel
    }
}
