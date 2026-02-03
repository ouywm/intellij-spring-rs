package com.springrs.plugin.quickfix

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.openapi.util.TextRange
import com.springrs.plugin.SpringRsBundle
import org.toml.lang.psi.TomlLiteral

abstract class SpringRsTomlQuickFixBase<E : PsiElement>(
    element: E
) : LocalQuickFixAndIntentionActionOnPsiElement(element) {

    final override fun getFamilyName(): String = text

    final override fun startInWriteAction(): Boolean = true

    abstract override fun getText(): String

    abstract fun applyFix(project: Project, editor: Editor?, element: E, file: PsiFile)

    final override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement
    ) {
        @Suppress("UNCHECKED_CAST")
        applyFix(project, editor, startElement as E, file)
    }

    protected fun replaceText(
        project: Project,
        file: PsiFile,
        range: TextRange,
        replacement: String,
        editor: Editor? = null,
        caretOffsetInReplacement: Int? = null
    ) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        document.replaceString(range.startOffset, range.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)

        if (editor != null && caretOffsetInReplacement != null) {
            editor.caretModel.moveToOffset(range.startOffset + caretOffsetInReplacement)
        }
    }
}

class SpringRsWrapWithQuotesFix(
    element: TomlLiteral
) : SpringRsTomlQuickFixBase<TomlLiteral>(element) {

    override fun getText(): String = SpringRsBundle.message("springrs.quickfix.add.quotes")

    override fun applyFix(project: Project, editor: Editor?, element: TomlLiteral, file: PsiFile) {
        val raw = element.text ?: return
        if (raw.startsWith("\"") || raw.startsWith("'")) return

        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
        val replacement = "\"$escaped\""
        replaceText(project, file, element.textRange, replacement, editor, caretOffsetInReplacement = replacement.length - 1)
    }
}

class SpringRsRemoveQuotesFix(
    element: TomlLiteral
) : SpringRsTomlQuickFixBase<TomlLiteral>(element) {

    override fun getText(): String = SpringRsBundle.message("springrs.quickfix.remove.quotes")

    override fun applyFix(project: Project, editor: Editor?, element: TomlLiteral, file: PsiFile) {
        val raw = element.text ?: return
        if (raw.length < 2) return

        val first = raw.first()
        val last = raw.last()
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            val unquoted = raw.substring(1, raw.length - 1)
            replaceText(project, file, element.textRange, unquoted, editor, caretOffsetInReplacement = unquoted.length)
        }
    }
}

class SpringRsWrapInArrayFix(
    element: TomlLiteral
) : SpringRsTomlQuickFixBase<TomlLiteral>(element) {

    override fun getText(): String = SpringRsBundle.message("springrs.quickfix.wrap.array")

    override fun applyFix(project: Project, editor: Editor?, element: TomlLiteral, file: PsiFile) {
        val raw = element.text ?: return
        if (raw.startsWith("[") && raw.endsWith("]")) return

        val replacement = "[$raw]"
        replaceText(project, file, element.textRange, replacement, editor, caretOffsetInReplacement = replacement.length - 1)
    }
}

class SpringRsWrapInInlineTableFix(
    element: PsiElement
) : SpringRsTomlQuickFixBase<PsiElement>(element) {

    override fun getText(): String = SpringRsBundle.message("springrs.quickfix.wrap.inline.table")

    override fun applyFix(project: Project, editor: Editor?, element: PsiElement, file: PsiFile) {
        val raw = element.text ?: return
        if (raw.startsWith("{") && raw.endsWith("}")) return

        val replacement = "{}"
        replaceText(project, file, element.textRange, replacement, editor, caretOffsetInReplacement = 1)
    }
}

class SpringRsReplaceWithEmptyArrayFix(
    element: PsiElement
) : SpringRsTomlQuickFixBase<PsiElement>(element) {

    override fun getText(): String = SpringRsBundle.message("springrs.quickfix.wrap.array")

    override fun applyFix(project: Project, editor: Editor?, element: PsiElement, file: PsiFile) {
        replaceText(project, file, element.textRange, "[]", editor, caretOffsetInReplacement = 1)
    }
}

class SpringRsReplaceTomlLiteralFix(
    element: TomlLiteral,
    private val replacementText: String,
    private val presentableValue: String
) : SpringRsTomlQuickFixBase<TomlLiteral>(element) {

    override fun getText(): String = SpringRsBundle.message("springrs.quickfix.replace.with", presentableValue)

    override fun applyFix(project: Project, editor: Editor?, element: TomlLiteral, file: PsiFile) {
        replaceText(project, file, element.textRange, replacementText, editor, caretOffsetInReplacement = replacementText.length)
    }
}
