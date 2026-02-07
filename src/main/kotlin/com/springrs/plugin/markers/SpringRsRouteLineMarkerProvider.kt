package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import com.springrs.plugin.routes.SpringRsConfigPrefixUtil
import com.springrs.plugin.routes.SpringRsRouteUtil
import com.springrs.plugin.toolwindow.SpringRsToolWindow
import com.springrs.plugin.utils.CargoUtils
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsMethodCall
import javax.swing.JList

/**
 * Gutter icons for spring-rs (axum) routes.
 *
 * Supported:
 * - spring_web attribute macros: #[get], #[post], #[route(...)] etc.
 * - native axum router builder: .route("/path", get(handler)) (and simple method-router chains)
 */
class SpringRsRouteLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.route.marker.name")

    override fun getIcon() = SpringRsIcons.RequestMapping

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        // Attribute-macro routes: place the icon on the function identifier line (not on the macro line),
        // so it can coexist with other Rust gutter icons on the attribute list.
        val fn = element.parent as? RsFunction
        if (fn != null && fn.identifier == element) {
            val infos = SpringRsRouteUtil.extractAttributeRoutes(fn)
            if (infos.isNotEmpty()) {
                val prefix = SpringRsRouteUtil.computeNestPrefix(fn)

                // Resolve global_prefix for the current crate.
                val globalPrefix = getGlobalPrefixForElement(element)

                val routes = infos.map { info ->
                    val pathWithNest = SpringRsRouteUtil.joinPaths(prefix, info.path)
                    val fullPath = if (globalPrefix != null) {
                        SpringRsRouteUtil.joinPaths(globalPrefix, pathWithNest)
                    } else {
                        pathWithNest
                    }
                    RouteInfo(info.method, fullPath)
                }

                // Do not use Rust's mergeable marker helper here: route icons should stay visible even when the Rust
                // plugin adds its own markers (e.g. impl/override).
                return LineMarkerInfo(
                    element,
                    element.textRange,
                    SpringRsIcons.RequestMapping,
                    { _: PsiElement -> routes.joinToString(separator = "\n") { "${it.method} ${it.path}" } },
                    PopupRoutesHandler(routes, element),
                    GutterIconRenderer.Alignment.LEFT,
                    { SpringRsBundle.message("springrs.route.marker.name") }
                )
            }
        }

        // Builder-based routes: show on `.route` identifier.
        val call = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, RsMethodCall::class.java) ?: return null
        if (call.identifier != element) return null
        if (call.referenceName != "route") return null

        val targets = SpringRsRouteUtil.extractRouterCallRouteTargets(call)
        if (targets.isEmpty()) return null

        // Resolve global_prefix for the current crate.
        val globalPrefix = getGlobalPrefixForElement(element)

        // Compute nest prefix in the Router call chain.
        val nestPrefix = SpringRsRouteUtil.computeRouterNestPrefix(call)

        val routes = targets.map { target ->
            // Apply nest prefix first.
            val pathWithNest = SpringRsRouteUtil.joinPaths(nestPrefix, target.path)
            // Then apply global_prefix.
            val fullPath = if (globalPrefix != null) {
                SpringRsRouteUtil.joinPaths(globalPrefix, pathWithNest)
            } else {
                pathWithNest
            }
            RouteInfo(target.method, fullPath)
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            SpringRsIcons.RequestMapping,
            { _: PsiElement -> routes.joinToString(separator = "\n") { "${it.method} ${it.path}" } },
            PopupRoutesHandler(routes, element),
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.route.marker.name") }
        )
    }

    /**
     * Resolves the crate-level global_prefix for the given PSI element.
     */
    private fun getGlobalPrefixForElement(element: PsiElement): String? {
        val project = element.project
        val filePath = element.containingFile?.virtualFile?.path ?: return null

        // Resolve crate root for the file.
        val crateRoot = CargoUtils.findCrateRootForFile(project, filePath) ?: return null

        // Read prefixes from config.
        val config = SpringRsConfigPrefixUtil.getConfigPrefixes(project, crateRoot)
        return config.globalPrefix
    }

    /**
     * Route info used by the popup and tool window navigation.
     */
    private data class RouteInfo(val method: String, val path: String) {
        override fun toString(): String = "$method $path"
    }

    /**
     * Popup navigation handler for the gutter icon.
     */
    private class PopupRoutesHandler(
        private val routes: List<RouteInfo>,
        private val element: PsiElement
    ) : GutterIconNavigationHandler<PsiElement> {

        override fun navigate(e: java.awt.event.MouseEvent?, elt: PsiElement) {
            if (routes.isEmpty() || e == null) return

            val project = element.project

            if (routes.size == 1) {
                // Single route: locate directly in the tool window.
                locateInToolWindow(project, routes[0])
            } else {
                // Multiple routes: choose one first, then locate.
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(routes)
                    .setTitle(SpringRsBundle.message("springrs.route.marker.select.route"))
                    .setRenderer(RouteInfoRenderer())
                    .setItemChosenCallback { selectedRoute ->
                        locateInToolWindow(project, selectedRoute)
                    }
                    .createPopup()

                popup.show(RelativePoint(e))
            }
        }

        /**
         * Locates the route in the tool window.
         */
        private fun locateInToolWindow(project: com.intellij.openapi.project.Project, route: RouteInfo) {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(SpringRsToolWindow.TOOL_WINDOW_ID) ?: return

            // Pass current file path to make matching more precise.
            val filePath = element.containingFile?.virtualFile?.path

            // Activate the tool window.
            toolWindow.activate {
                // Get tool window content.
                val content = toolWindow.contentManager.getContent(0) ?: return@activate
                val component = content.component

                // Find SpringRsToolWindow instance and locate the route (including file path).
                findRoutesToolWindow(component)?.locateRoute(route.method, route.path, filePath)
            }
        }

        /**
         * Finds SpringRsToolWindow from the component tree.
         */
        private fun findRoutesToolWindow(component: java.awt.Component): SpringRsToolWindow? {
            // Tool window content is a panel returned by SpringRsToolWindow.getContent().
            // We retrieve the instance via client property.
            if (component is javax.swing.JComponent) {
                val toolWindow = component.getClientProperty(SpringRsToolWindow.TOOL_WINDOW_KEY)
                if (toolWindow is SpringRsToolWindow) {
                    return toolWindow
                }
            }
            return null
        }
    }

    private class RouteInfoRenderer : ColoredListCellRenderer<RouteInfo>() {
        override fun customizeCellRenderer(
            list: JList<out RouteInfo>,
            value: RouteInfo?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return

            icon = SpringRsIcons.RequestMapping

            val method = value.method.uppercase()
            append(method, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(value.path, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
