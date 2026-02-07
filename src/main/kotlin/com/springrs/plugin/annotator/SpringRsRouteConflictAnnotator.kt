package com.springrs.plugin.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.routes.SpringRsRouteUtil
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsMethodCall
import org.rust.lang.core.psi.ext.stringValue

/**
 * Annotator that detects route path conflicts within the same file.
 *
 * Uses local PSI scanning (not the global index) to avoid cache timing issues
 * during editing. Detects:
 * - Two attribute-macro routes with the same method+path in the same file
 * - Two Router builder `.route()` calls with the same path in the same file
 */
class SpringRsRouteConflictAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (DumbService.isDumb(element.project)) return

        // --- Attribute routes: #[get("/path")] on function identifiers ---
        val fn = element.parent as? RsFunction
        if (fn != null && fn.identifier == element) {
            val myRoutes = SpringRsRouteUtil.extractAttributeRoutes(fn)
            if (myRoutes.isEmpty()) return

            val nestPrefix = SpringRsRouteUtil.computeNestPrefix(fn)
            val rsFile = fn.containingFile as? RsFile ?: return

            // Collect all other route functions in the same file.
            val otherFunctions = PsiTreeUtil.findChildrenOfType(rsFile, RsFunction::class.java)
                .filter { it !== fn }

            for (myRoute in myRoutes) {
                val myFullPath = SpringRsRouteUtil.joinPaths(nestPrefix, myRoute.path)

                // Check for conflicts in other functions.
                val conflictNames = mutableListOf<String>()
                for (otherFn in otherFunctions) {
                    val otherNest = SpringRsRouteUtil.computeNestPrefix(otherFn)
                    val otherRoutes = SpringRsRouteUtil.extractAttributeRoutes(otherFn)
                    for (otherRoute in otherRoutes) {
                        val otherFullPath = SpringRsRouteUtil.joinPaths(otherNest, otherRoute.path)
                        if (otherRoute.method.equals(myRoute.method, ignoreCase = true) && otherFullPath == myFullPath) {
                            conflictNames.add(otherFn.name ?: "?")
                        }
                    }
                }

                if (conflictNames.isNotEmpty()) {
                    val locations = conflictNames.take(3).joinToString(", ")
                    val suffix = if (conflictNames.size > 3) " (+${conflictNames.size - 3})" else ""
                    holder.newAnnotation(
                        HighlightSeverity.WARNING,
                        SpringRsBundle.message("springrs.route.conflict.message", myRoute.method, myFullPath, "$locations$suffix")
                    ).range(element.textRange).create()
                    return // One annotation per function is enough.
                }
            }
            return
        }

        // --- Router builder: `.route("/path", get(handler))` ---
        val call = element.parent as? RsMethodCall
        if (call != null && call.identifier == element && call.referenceName == "route") {
            val myRoutes = SpringRsRouteUtil.extractRouterCallRoutes(call)
            if (myRoutes.isEmpty()) return

            val rsFile = call.containingFile as? RsFile ?: return

            // Collect all other `.route()` calls in the same file.
            val allRouteCalls = PsiTreeUtil.findChildrenOfType(rsFile, RsMethodCall::class.java)
                .filter { it !== call && it.referenceName == "route" }

            for (myRoute in myRoutes) {
                val conflictHandlers = mutableListOf<String>()
                for (otherCall in allRouteCalls) {
                    val otherRoutes = SpringRsRouteUtil.extractRouterCallRoutes(otherCall)
                    for (otherRoute in otherRoutes) {
                        if (otherRoute.method.equals(myRoute.method, ignoreCase = true) && otherRoute.path == myRoute.path) {
                            conflictHandlers.add(otherRoute.handlerName ?: "?")
                        }
                    }
                }

                if (conflictHandlers.isNotEmpty()) {
                    val locations = conflictHandlers.take(3).joinToString(", ")
                    val suffix = if (conflictHandlers.size > 3) " (+${conflictHandlers.size - 3})" else ""
                    holder.newAnnotation(
                        HighlightSeverity.WARNING,
                        SpringRsBundle.message("springrs.route.conflict.message", myRoute.method, myRoute.path, "$locations$suffix")
                    ).range(element.textRange).create()
                    return
                }
            }
        }
    }
}
