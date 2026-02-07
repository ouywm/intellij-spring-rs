package com.springrs.plugin.routes

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.utils.FilePathValidator
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction

/**
 * Collects spring-rs job (scheduled task) definitions from Rust source code.
 *
 * Scans for functions annotated with:
 * - #[cron("...")]     — cron-based scheduling
 * - #[fix_delay(...)]  — fixed delay between executions
 * - #[fix_rate(...)]   — fixed rate execution
 * - #[one_shot(...)]   — one-time execution after delay
 *
 * Used by:
 * - job gutter icons (line markers)
 * - tool window (global view)
 */
object SpringRsJobIndex {

    data class Job(
        val type: SpringRsJobUtil.JobType,
        val expression: String,
        val handlerName: String?,
        val file: VirtualFile,
        val offset: Int
    )

    private val JOBS_KEY: Key<CachedValue<List<Job>>> =
        Key.create("com.springrs.plugin.routes.SpringRsJobIndex.JOBS")

    fun getJobsCached(project: Project): List<Job> {
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            JOBS_KEY,
            {
                val jobs = buildJobs(project)
                CachedValueProvider.Result.create(
                    jobs,
                    SpringRsRouteModificationTracker.getInstance(project)
                )
            },
            false
        )
    }

    private fun buildJobs(project: Project): List<Job> {
        val jobs = mutableListOf<Job>()
        val scope = GlobalSearchScope.projectScope(project)

        for (vFile in FileTypeIndex.getFiles(RsFileType, scope)) {
            ProgressManager.checkCanceled()

            // Skip macro-expanded files.
            if (FilePathValidator.isMacroExpanded(vFile.path)) continue

            val rsFile = PsiManager.getInstance(project).findFile(vFile) as? RsFile ?: continue

            for (fn in PsiTreeUtil.findChildrenOfType(rsFile, RsFunction::class.java)) {
                ProgressManager.checkCanceled()
                collectJobs(vFile, fn, jobs)
            }
        }

        return jobs
            .distinctBy { "${it.type} ${it.handlerName} ${it.file.path}:${it.offset}" }
            .sortedWith(compareBy<Job> { it.type.ordinal }.thenBy { it.handlerName ?: "" }.thenBy { it.file.path })
    }

    private fun collectJobs(vFile: VirtualFile, fn: RsFunction, out: MutableList<Job>) {
        val jobInfo = SpringRsJobUtil.extractJobInfo(fn) ?: return
        val fnName = fn.name
        val offset = fn.identifier.textOffset

        out.add(
            Job(
                type = jobInfo.type,
                expression = jobInfo.expression,
                handlerName = fnName,
                file = vFile,
                offset = offset
            )
        )
    }
}
