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
import com.springrs.plugin.routes.SpringRsStreamListenerUtil
import com.springrs.plugin.toolwindow.SpringRsToolWindow
import org.rust.lang.core.psi.RsFunction

/**
 * Gutter icons for spring-rs stream listeners.
 *
 * Shows a listener icon on functions annotated with #[stream_listener(...)].
 * Clicking navigates to the stream listener in the tool window.
 */
class SpringRsStreamListenerLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.stream.marker.name")

    override fun getIcon() = SpringRsIcons.StreamListener

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        val fn = element.parent as? RsFunction ?: return null
        if (fn.identifier != element) return null

        val info = SpringRsStreamListenerUtil.extractStreamListenerInfo(fn) ?: return null

        val fnName = fn.name ?: "unknown"
        val tooltip = buildTooltip(fnName, info)

        return LineMarkerInfo(
            element,
            element.textRange,
            SpringRsIcons.StreamListener,
            { _: PsiElement -> tooltip },
            StreamListenerNavigationHandler(fnName, info),
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.stream.marker.name") }
        )
    }

    private fun buildTooltip(fnName: String, info: SpringRsStreamListenerUtil.StreamListenerInfo): String {
        val sb = StringBuilder()
        sb.append("spring-rs Stream Listener: $fnName")
        sb.append("\nTopics: ${info.topicsDisplay()}")

        info.consumerMode?.let { sb.append("\nMode: $it") }
        info.groupId?.let { sb.append("\nGroup: $it") }
        info.optionsType?.let { type ->
            sb.append("\nBackend: $type")
            info.optionsFunction?.let { sb.append(" (options: $it)") }
        }

        return sb.toString()
    }

    /**
     * Navigation handler: opens the tool window and locates the stream listener.
     */
    private class StreamListenerNavigationHandler(
        private val handlerName: String,
        private val info: SpringRsStreamListenerUtil.StreamListenerInfo
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
                    tw.locateStreamListener(handlerName, info.primaryTopic, filePath)
                }
            }
        }
    }
}
