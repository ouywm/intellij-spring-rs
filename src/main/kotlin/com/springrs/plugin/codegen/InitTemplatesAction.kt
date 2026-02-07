package com.springrs.plugin.codegen

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.springrs.plugin.SpringRsBundle
import java.io.File

/**
 * Action to initialize custom Velocity templates in the project directory.
 *
 * Copies built-in templates to `{project}/.spring-rs/templates/` so the user
 * can edit them directly in the IDE editor (like EasyCode's project-level templates).
 *
 * If templates already exist, opens the directory without overwriting.
 *
 * Can be triggered from: Tools menu → "Initialize Sea-ORM Templates"
 */
class InitTemplatesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val templateDir = File(basePath, TEMPLATE_DIR)
        val isNew = !templateDir.exists()
        templateDir.mkdirs()

        // Copy built-in templates (only if file doesn't exist — never overwrite)
        for (name in TEMPLATE_NAMES) {
            val targetFile = File(templateDir, "$name.rs.vm")
            if (targetFile.exists()) continue

            val resourcePath = "/codegen-templates/$name.rs.vm"
            val content = InitTemplatesAction::class.java.getResourceAsStream(resourcePath)
                ?.use { it.bufferedReader().readText() }
                ?: continue

            targetFile.writeText(content)
        }

        // Refresh VFS to show files in project tree
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(templateDir)?.let { vDir ->
            VfsUtil.markDirtyAndRefresh(false, true, true, vDir)

            // Open the first template in the editor
            vDir.findChild("entity.rs.vm")?.let { entityVm ->
                FileEditorManager.getInstance(project).openFile(entityVm, true)
            }
        }

        // Auto-enable custom templates in settings
        val settings = CodeGenSettingsState.getInstance(project)
        settings.useCustomTemplate = true
        settings.customTemplatePath = TEMPLATE_DIR

        val message = if (isNew)
            SpringRsBundle.message("codegen.templates.init.created", TEMPLATE_DIR)
        else
            SpringRsBundle.message("codegen.templates.init.opened", TEMPLATE_DIR)

        Notifications.Bus.notify(
            Notification("SpringRs.Notifications",
                SpringRsBundle.message("codegen.notification.title"), message, NotificationType.INFORMATION),
            project
        )
    }

    companion object {
        const val TEMPLATE_DIR = ".spring-rs/templates"
        private val TEMPLATE_NAMES = listOf("entity", "dto", "vo", "service", "route")
    }
}
