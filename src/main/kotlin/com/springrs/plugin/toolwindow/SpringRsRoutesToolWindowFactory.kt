package com.springrs.plugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.springrs.plugin.SpringRsBundle

class SpringRsRoutesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Keep `id` stable for lookup, but localize the visible title.
        toolWindow.stripeTitle = SpringRsBundle.message("springrs.route.marker.name")
        toolWindow.title = SpringRsBundle.message("springrs.route.marker.name")

        val routesToolWindow = SpringRsRoutesToolWindow(project)
        val content = ContentFactory.getInstance().createContent(
            routesToolWindow.getContent(),
            "",
            false
        )
        toolWindow.contentManager.addContent(content)

        // Tie all listeners/async tasks in this toolwindow to the toolwindow's lifecycle (not the Project).
        Disposer.register(toolWindow.disposable, routesToolWindow)
    }
}
