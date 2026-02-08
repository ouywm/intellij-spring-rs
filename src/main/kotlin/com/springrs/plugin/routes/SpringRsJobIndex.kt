package com.springrs.plugin.routes

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collects spring-rs job (scheduled task) definitions from Rust source code.
 *
 * Delegates to [SpringRsUnifiedScanner] for the actual scanning, which collects
 * routes, jobs, stream listeners, and components in a single pass.
 */
object SpringRsJobIndex {

    data class Job(
        val type: SpringRsJobUtil.JobType,
        val expression: String,
        val handlerName: String?,
        val file: VirtualFile,
        val offset: Int
    )

    /**
     * Returns cached jobs. Delegates to [SpringRsUnifiedScanner] so that
     * all item types share a single project scan.
     */
    fun getJobsCached(project: Project): List<Job> {
        if (DumbService.isDumb(project)) return emptyList()
        return SpringRsUnifiedScanner.getScanResultCached(project).jobs
    }
}
