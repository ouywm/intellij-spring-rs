package com.springrs.plugin.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.springrs.plugin.model.ConfigFieldModel
import com.springrs.plugin.utils.RustTypeUtils

/**
 * Completion insert-handler utilities.
 *
 * Provides a consistent, field-type-aware insertion logic.
 */
object CompletionInsertHandlerUtils {



    /**
     * Insert a value template based on the field type.
     *
     * @param field config field model
     * @param insertContext insertion context
     */
    fun insertValueTemplateByFieldType(field: ConfigFieldModel, insertContext: InsertionContext) {
        val editor = insertContext.editor
        val document = editor.document
        val offset = insertContext.tailOffset
        val project = editor.project ?: return

        // If there is already an "=" (or " = "), don't insert again.
        val textAfter = document.getText(com.intellij.openapi.util.TextRange(offset, minOf(offset + 10, document.textLength)))
        if (textAfter.trimStart().startsWith("=")) {
            // An equals sign already exists; just move the caret after it.
            val eqIndex = textAfter.indexOf('=')
            if (eqIndex >= 0) {
                editor.caretModel.moveToOffset(offset + eqIndex + 1)
                // Skip the whitespace after '='.
                val afterEq = document.getText(com.intellij.openapi.util.TextRange(offset + eqIndex + 1, minOf(offset + eqIndex + 3, document.textLength)))
                if (afterEq.startsWith(" ")) {
                    editor.caretModel.moveToOffset(offset + eqIndex + 2)
                }
            }
            return
        }

        when {
            RustTypeUtils.isBoolType(field.type) -> {
                // bool: insert " = ", caret after '=', and trigger completion.
                insertAndMoveCursor(document, editor, offset, " = ", 3, project, autoPopup = true)
            }
            RustTypeUtils.isVecType(field.type) -> {
                // Vec: insert " = []", caret inside brackets.
                insertAndMoveCursor(document, editor, offset, " = []", 4, project, autoPopup = false)
            }
            RustTypeUtils.isMapType(field.type) -> {
                // Map: insert " = {}", caret inside braces.
                // Note: In TOML, Map-like values are represented as inline tables; users input pairs manually.
                insertAndMoveCursor(document, editor, offset, " = {}", 4, project, autoPopup = false)
            }
            field.isEnumType -> {
                // Enum: insert ` = ""`, caret inside quotes, and trigger completion.
                insertAndMoveCursor(document, editor, offset, " = \"\"", 4, project, autoPopup = true)
            }
            RustTypeUtils.isStringLikeType(field.type) -> {
                // String (and string-like types): insert ` = ""`, caret inside quotes.
                // If there is a default value, trigger auto-popup to show suggestions.
                val hasDefault = field.defaultValue != null
                insertAndMoveCursor(document, editor, offset, " = \"\"", 4, project, autoPopup = hasDefault)
            }
            field.isStructType -> {
                // Struct: insert " = {}", caret inside braces, and trigger completion.
                insertAndMoveCursor(document, editor, offset, " = {}", 4, project, autoPopup = true)
            }
            else -> {
                // Other types: insert " = ".
                // If there is a default value (e.g. numeric types), trigger auto-popup.
                val hasDefault = field.defaultValue != null
                insertAndMoveCursor(document, editor, offset, " = ", 3, project, autoPopup = hasDefault)
            }
        }
    }

    /**
     * Insert text and move the caret.
     *
     * @param document document
     * @param editor editor
     * @param offset insertion offset
     * @param text text to insert
     * @param cursorOffset caret offset relative to insertion offset
     * @param project project
     * @param autoPopup whether to trigger completion automatically
     */
    private fun insertAndMoveCursor(
        document: com.intellij.openapi.editor.Document,
        editor: Editor,
        offset: Int,
        text: String,
        cursorOffset: Int,
        project: Project,
        autoPopup: Boolean
    ) {
        document.insertString(offset, text)
        // Ensure PSI is up-to-date before triggering another completion (e.g. enum value auto-popup).
        PsiDocumentManager.getInstance(project).commitDocument(document)
        editor.caretModel.moveToOffset(offset + cursorOffset)
        if (autoPopup) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
    }
}
