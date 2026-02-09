package com.springrs.plugin.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.routes.SpringRsRouteIndex
import com.springrs.plugin.routes.SpringRsRouteUtil
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsMethodCall

/**
 * Annotator that detects route path conflicts within the same crate.
 *
 * Uses [SpringRsRouteIndex] cached results instead of scanning files on every
 * annotation pass, reducing complexity from O(N) per-annotate to O(1) cache lookup.
 *
 * Conflict annotations include a quick-fix to navigate to the conflicting function.
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
            val myFile = rsFile.virtualFile ?: return

            // Use cached routes instead of scanning the entire crate.
            val crateRoot = findCrateRoot(rsFile)
            val allRoutes = SpringRsRouteIndex.getRoutesCached(element.project)

            for (myRoute in myRoutes) {
                val myFullPath = SpringRsRouteUtil.joinPaths(nestPrefix, myRoute.path)

                // Find conflicts: same method + same path, different location, same crate.
                val conflicts = allRoutes.filter { other ->
                    other.method.equals(myRoute.method, ignoreCase = true)
                        && other.fullPath == myFullPath
                        && !(other.file == myFile && other.offset == fn.identifier.textOffset)
                        && isSameCrate(crateRoot, other.file.path)
                }

                if (conflicts.isNotEmpty()) {
                    val names = conflicts.take(3).joinToString(", ") { it.handlerName ?: "?" }
                    val suffix = if (conflicts.size > 3) " (+${conflicts.size - 3})" else ""
                    val builder = holder.newAnnotation(
                        HighlightSeverity.WARNING,
                        SpringRsBundle.message("springrs.route.conflict.message", myRoute.method, myFullPath, "$names$suffix")
                    ).range(element.textRange)

                    for (target in conflicts.take(5)) {
                        builder.withFix(NavigateToRouteFix(target))
                    }
                    builder.create()
                    return
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
            val myFile = rsFile.virtualFile ?: return
            val crateRoot = findCrateRoot(rsFile)
            val allRoutes = SpringRsRouteIndex.getRoutesCached(element.project)

            for (myRoute in myRoutes) {
                val conflicts = allRoutes.filter { other ->
                    other.method.equals(myRoute.method, ignoreCase = true)
                        && other.fullPath == myRoute.path
                        && !(other.file == myFile && other.offset == call.textOffset)
                        && isSameCrate(crateRoot, other.file.path)
                }

                if (conflicts.isNotEmpty()) {
                    val names = conflicts.take(3).joinToString(", ") { it.handlerName ?: "?" }
                    val suffix = if (conflicts.size > 3) " (+${conflicts.size - 3})" else ""
                    val builder = holder.newAnnotation(
                        HighlightSeverity.WARNING,
                        SpringRsBundle.message("springrs.route.conflict.message", myRoute.method, myRoute.path, "$names$suffix")
                    ).range(element.textRange)

                    for (target in conflicts.take(5)) {
                        builder.withFix(NavigateToRouteFix(target))
                    }
                    builder.create()
                    return
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Quick-fix: navigate to conflicting route
    // ══════════════════════════════════════════════════════════════

    private class NavigateToRouteFix(private val route: SpringRsRouteIndex.Route) : IntentionAction {
        private val label = route.handlerName ?: "?"
        private val fileName = route.file.name

        override fun getText(): String = SpringRsBundle.message("springrs.route.conflict.navigate", label, fileName)
        override fun getFamilyName(): String = "Navigate to route conflict"
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = route.file.isValid
        override fun startInWriteAction(): Boolean = false

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            FileEditorManager.getInstance(project).openEditor(
                OpenFileDescriptor(project, route.file, route.offset), true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Crate-scoped helpers
    // ══════════════════════════════════════════════════════════════

    private fun findCrateRoot(rsFile: RsFile): String? {
        var dir = rsFile.virtualFile?.parent
        while (dir != null) {
            if (dir.findChild("Cargo.toml") != null) return dir.path
            dir = dir.parent
        }
        return null
    }

    private fun isSameCrate(crateRoot: String?, filePath: String): Boolean {
        if (crateRoot == null) return true
        val normalizedRoot = crateRoot.trimEnd('/') + "/"
        return filePath.startsWith(normalizedRoot) || filePath == crateRoot
    }
}
