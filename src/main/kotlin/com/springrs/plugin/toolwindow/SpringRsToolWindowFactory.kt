package com.springrs.plugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.springrs.plugin.SpringRsBundle

class SpringRsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Keep `id` stable for lookup, but localize the visible title.
        toolWindow.stripeTitle = SpringRsBundle.message("springrs.toolwindow.title")
        toolWindow.title = SpringRsBundle.message("springrs.toolwindow.title")

        val springRsToolWindow = SpringRsToolWindow(project)
        val content = ContentFactory.getInstance().createContent(
            springRsToolWindow.getContent(),
            "",
            false
        )
        toolWindow.contentManager.addContent(content)

        Disposer.register(toolWindow.disposable, springRsToolWindow)
    }
}
