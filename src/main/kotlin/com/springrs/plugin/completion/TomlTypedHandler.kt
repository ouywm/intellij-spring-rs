package com.springrs.plugin.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.toml.lang.psi.TomlFile

/**
 * TOML typed handler.
 *
 * Triggers completion automatically on specific typed characters.
 *
 * Triggers:
 * - typing `[` shows section completion
 */
class TomlTypedHandler : TypedHandlerDelegate() {

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        // Only handle TOML files.
        if (file !is TomlFile) {
            return Result.CONTINUE
        }

        // Only handle spring-rs config files.
        if (!SpringRsConfigFileUtil.isConfigFileName(file.name)) {
            return Result.CONTINUE
        }

        // When typing '[', trigger completion.
        if (charTyped == '[') {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }

        return Result.CONTINUE
    }
}
