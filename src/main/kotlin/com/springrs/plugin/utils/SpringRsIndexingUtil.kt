package com.springrs.plugin.utils

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiManager
import java.util.concurrent.atomic.AtomicBoolean

object SpringRsIndexingUtil {

    private val RESTART_SCHEDULED_KEY: Key<AtomicBoolean> =
        Key.create("com.springrs.plugin.utils.SpringRsIndexingUtil.RESTART_SCHEDULED")

    /**
     * Schedules a daemon restart once the IDE returns to Smart Mode.
     *
     * This is used to force re-highlighting after indexing finishes, so users don't need
     * to close/reopen `app.toml` to clear temporary errors.
     */
    fun scheduleDaemonRestartWhenSmart(project: Project) {
        val scheduled = project.getUserData(RESTART_SCHEDULED_KEY) ?: AtomicBoolean(false).also {
            project.putUserData(RESTART_SCHEDULED_KEY, it)
        }

        if (!scheduled.compareAndSet(false, true)) return

        DumbService.getInstance(project).runWhenSmart {
            try {
                val analyzer = DaemonCodeAnalyzer.getInstance(project)
                val psiManager = PsiManager.getInstance(project)
                FileEditorManager.getInstance(project).openFiles.forEach { vf ->
                    psiManager.findFile(vf)?.let { psiFile ->
                        analyzer.restart(psiFile)
                    }
                }
            } finally {
                scheduled.set(false)
            }
        }
    }
}

