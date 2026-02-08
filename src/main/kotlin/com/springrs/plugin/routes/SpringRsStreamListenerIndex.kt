package com.springrs.plugin.routes

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collects spring-rs stream listener definitions from Rust source code.
 *
 * Delegates to [SpringRsUnifiedScanner] for the actual scanning, which collects
 * routes, jobs, stream listeners, and components in a single pass.
 */
object SpringRsStreamListenerIndex {

    data class StreamListener(
        val topics: List<String>,
        val handlerName: String?,
        val file: VirtualFile,
        val offset: Int,
        val consumerMode: String? = null,
        val groupId: String? = null,
        val optionsType: String? = null
    ) {
        /** Primary topic for display. */
        val primaryTopic: String get() = topics.firstOrNull() ?: "?"

        /** All topics as display string. */
        fun topicsDisplay(): String = topics.joinToString(", ")
    }

    /**
     * Returns cached stream listeners. Delegates to [SpringRsUnifiedScanner] so that
     * all item types share a single project scan.
     */
    fun getListenersCached(project: Project): List<StreamListener> {
        if (DumbService.isDumb(project)) return emptyList()
        return SpringRsUnifiedScanner.getScanResultCached(project).streamListeners
    }
}
