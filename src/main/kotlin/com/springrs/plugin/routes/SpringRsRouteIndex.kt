package com.springrs.plugin.routes

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collects spring-rs (axum) routes from Rust source code.
 *
 * Delegates to [SpringRsUnifiedScanner] for the actual scanning, which collects
 * routes, jobs, stream listeners, and components in a single pass.
 */
object SpringRsRouteIndex {

    data class Route(
        val method: String,
        val fullPath: String,
        val handlerName: String?,
        val file: VirtualFile,
        val offset: Int,
        val kind: Kind
    ) {
        enum class Kind { ATTRIBUTE, ROUTE_CALL }
    }

    /**
     * Returns cached routes. Delegates to [SpringRsUnifiedScanner] so that
     * all item types share a single project scan.
     */
    fun getRoutesCached(project: Project): List<Route> {
        if (DumbService.isDumb(project)) return emptyList()
        return SpringRsUnifiedScanner.getScanResultCached(project).routes
    }
}
