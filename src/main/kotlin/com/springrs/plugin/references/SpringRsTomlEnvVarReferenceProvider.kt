package com.springrs.plugin.references

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.CargoUtils
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind
import java.io.File

/**
 * Reference provider for `${VAR}` environment variable references in TOML string values.
 *
 * Enables Ctrl+Click navigation from `${VAR_NAME}` or `${VAR_NAME:default}` in TOML strings
 * to the corresponding line in `.env` files.
 *
 * Resolution order:
 * 1. Current crate root `.env`
 * 2. Workspace/project root `.env`
 */
class SpringRsTomlEnvVarReferenceProvider : PsiReferenceProvider() {

    companion object {
        /** Regex to find `${VAR}` or `${VAR:default}` patterns within a string. */
        private val ENV_VAR_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}""")
    }

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? TomlLiteral ?: return PsiReference.EMPTY_ARRAY
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(literal)) return PsiReference.EMPTY_ARRAY
        if (DumbService.isDumb(literal.project)) return PsiReference.EMPTY_ARRAY

        // Only handle string literals.
        if (literal.kind !is TomlLiteralKind.String) return PsiReference.EMPTY_ARRAY

        val text = literal.text
        val project = literal.project

        // Resolve crate root for scoped .env reading.
        val filePath = literal.containingFile?.virtualFile?.path
        val crateRoot = if (filePath != null) CargoUtils.findCrateRootForFile(project, filePath) else null

        // Collect .env file paths to search.
        val envFiles = mutableListOf<File>()
        if (crateRoot != null) envFiles.add(File(crateRoot, ".env"))
        val projectBase = project.basePath
        if (projectBase != null && projectBase != crateRoot) envFiles.add(File(projectBase, ".env"))

        val references = mutableListOf<PsiReference>()

        // Find all ${VAR} patterns in the string.
        ENV_VAR_PATTERN.findAll(text).forEach { match ->
            val varName = match.groupValues[1]

            // Only create a reference if the variable is actually defined in a .env file.
            // This avoids "Cannot resolve symbol" errors for variables that are only
            // in system env or not yet defined.
            if (!isDefinedInEnvFiles(varName, envFiles)) return@forEach

            val nameStart = match.range.first + 2 // skip `${`
            val nameEnd = nameStart + varName.length
            val range = TextRange(nameStart, nameEnd)

            references.add(EnvVarReference(literal, range, varName))
        }

        return references.toTypedArray()
    }

    /**
     * Quick check: is the variable defined in any of the given .env files?
     */
    private fun isDefinedInEnvFiles(varName: String, envFiles: List<File>): Boolean {
        for (envFile in envFiles) {
            if (!envFile.exists() || !envFile.isFile) continue
            try {
                envFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
                    val eqIndex = trimmed.indexOf('=')
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        if (key == varName) return true
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }
        return false
    }

    /**
     * Reference from a TOML `${VAR}` to the `.env` file definition.
     */
    private class EnvVarReference(
        element: TomlLiteral,
        rangeInElement: TextRange,
        private val varName: String
    ) : PsiReferenceBase<TomlLiteral>(element, rangeInElement, true) {

        override fun resolve(): PsiElement? {
            val project = element.project

            // Find the crate root for the current file.
            val filePath = element.containingFile?.virtualFile?.path
            val crateRoot = if (filePath != null) {
                CargoUtils.findCrateRootForFile(project, filePath)
            } else null

            // Try crate-level .env first, then workspace root .env.
            val envFiles = mutableListOf<File>()
            if (crateRoot != null) {
                envFiles.add(File(crateRoot, ".env"))
            }
            val projectBase = project.basePath
            if (projectBase != null && projectBase != crateRoot) {
                envFiles.add(File(projectBase, ".env"))
            }

            for (envFile in envFiles) {
                val target = findVarInEnvFile(project, envFile, varName)
                if (target != null) return target
            }

            return null
        }

        /**
         * Find the PsiElement for a variable definition in a `.env` file.
         *
         * Returns the PsiElement at the line where `VAR_NAME=...` is defined.
         */
        private fun findVarInEnvFile(project: Project, envFile: File, varName: String): PsiElement? {
            if (!envFile.exists() || !envFile.isFile) return null

            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(envFile) ?: return null
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null

            // Scan the file line by line to find `VAR_NAME=...`.
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

            for (lineNum in 0 until document.lineCount) {
                val lineStart = document.getLineStartOffset(lineNum)
                val lineEnd = document.getLineEndOffset(lineNum)
                val lineText = document.getText(TextRange(lineStart, lineEnd)).trim()

                // Skip comments and empty lines.
                if (lineText.isEmpty() || lineText.startsWith('#')) continue

                val eqIndex = lineText.indexOf('=')
                if (eqIndex <= 0) continue

                val key = lineText.substring(0, eqIndex).trim()
                if (key == varName) {
                    // Return the PsiElement at this line's start.
                    return psiFile.findElementAt(lineStart)
                }
            }

            return null
        }
    }
}
