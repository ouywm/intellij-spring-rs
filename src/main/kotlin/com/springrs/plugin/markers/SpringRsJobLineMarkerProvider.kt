package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import com.springrs.plugin.routes.SpringRsJobUtil
import com.springrs.plugin.toolwindow.SpringRsToolWindow
import org.rust.lang.core.psi.RsFunction

/**
 * Gutter icons for spring-rs scheduled jobs.
 *
 * Shows a clock icon on functions annotated with:
 * - #[cron("...")]
 * - #[fix_delay(...)]
 * - #[fix_rate(...)]
 * - #[one_shot(...)]
 *
 * Clicking the icon navigates to the job in the tool window.
 */
class SpringRsJobLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.job.marker.name")

    override fun getIcon() = SpringRsIcons.Job

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        // Only handle function identifiers.
        val fn = element.parent as? RsFunction ?: return null
        if (fn.identifier != element) return null

        val jobInfo = SpringRsJobUtil.extractJobInfo(fn) ?: return null

        val displayText = SpringRsJobUtil.formatJobExpression(jobInfo)
        val fnName = fn.name ?: "unknown"

        val tooltip = SpringRsBundle.message("springrs.job.marker.tooltip", fnName, displayText)

        return LineMarkerInfo(
            element,
            element.textRange,
            SpringRsIcons.Job,
            { _: PsiElement -> tooltip },
            JobNavigationHandler(fnName, jobInfo),
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.job.marker.name") }
        )
    }

    /**
     * Navigation handler that opens the tool window and locates the job.
     */
    private class JobNavigationHandler(
        private val handlerName: String,
        private val jobInfo: SpringRsJobUtil.JobInfo
    ) : GutterIconNavigationHandler<PsiElement> {

        override fun navigate(e: java.awt.event.MouseEvent?, elt: PsiElement) {
            if (e == null) return

            val project = elt.project
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(SpringRsToolWindow.TOOL_WINDOW_ID) ?: return

            val filePath = elt.containingFile?.virtualFile?.path

            toolWindow.activate {
                val content = toolWindow.contentManager.getContent(0) ?: return@activate
                val component = content.component

                val tw = component.getClientProperty(SpringRsToolWindow.TOOL_WINDOW_KEY)
                if (tw is SpringRsToolWindow) {
                    tw.locateJob(handlerName, jobInfo.type.displayName, filePath)
                }
            }
        }
    }
}
