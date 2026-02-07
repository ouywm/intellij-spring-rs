package com.springrs.plugin.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.CargoUtils
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind
import java.io.File

/**
 * Documentation provider for `${VAR}` environment variable references in TOML config files.
 *
 * Shows on hover:
 * - Variable name
 * - Current runtime value (from system env or .env file)
 * - Default value (if specified in the `${VAR:default}` pattern)
 * - Source (.env file path or "system environment")
 */
class SpringRsEnvVarDocumentationProvider : AbstractDocumentationProvider() {

    companion object {
        private val ENV_VAR_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}""")
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val original = originalElement ?: return null
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(original)) return null

        // Find the enclosing TOML string literal.
        val literal = PsiTreeUtil.getParentOfType(original, TomlLiteral::class.java) ?: (original as? TomlLiteral) ?: return null
        if (literal.kind !is TomlLiteralKind.String) return null

        val text = literal.text
        val cursorOffsetInLiteral = original.textOffset - literal.textOffset

        // Find which ${VAR} the cursor is on.
        val matchAtCursor = ENV_VAR_PATTERN.findAll(text).firstOrNull { match ->
            cursorOffsetInLiteral >= match.range.first && cursorOffsetInLiteral <= match.range.last + 1
        } ?: return null

        val varName = matchAtCursor.groupValues[1]
        val defaultValue = matchAtCursor.groupValues[2].takeIf { it.isNotEmpty() }

        val project = original.project
        val filePath = original.containingFile?.virtualFile?.path
        val crateRoot = if (filePath != null) CargoUtils.findCrateRootForFile(project, filePath) else null

        // Resolve the variable value and source.
        val resolution = resolveEnvVar(project, crateRoot, varName)

        return buildDocHtml(varName, defaultValue, resolution)
    }

    private data class EnvVarResolution(
        val value: String?,
        val source: String  // e.g., ".env (/path/to/.env)" or "system environment"
    )

    private fun resolveEnvVar(project: Project, crateRoot: String?, varName: String): EnvVarResolution? {
        // 1. Check crate .env file.
        if (crateRoot != null) {
            val envFile = File(crateRoot, ".env")
            findInEnvFile(envFile, varName)?.let { value ->
                return EnvVarResolution(value, ".env (${envFile.path})")
            }
        }

        // 2. Check workspace root .env file.
        val projectBase = project.basePath
        if (projectBase != null && projectBase != crateRoot) {
            val envFile = File(projectBase, ".env")
            findInEnvFile(envFile, varName)?.let { value ->
                return EnvVarResolution(value, ".env (${envFile.path})")
            }
        }

        // 3. Check system environment.
        try {
            val sysValue = System.getenv(varName)
            if (sysValue != null) {
                return EnvVarResolution(sysValue, "system environment")
            }
        } catch (_: SecurityException) { /* ignore */ }

        return null
    }

    private fun findInEnvFile(file: File, varName: String): String? {
        if (!file.exists() || !file.isFile) return null
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex > 0) {
                    val key = trimmed.substring(0, eqIndex).trim()
                    if (key == varName) {
                        var value = trimmed.substring(eqIndex + 1).trim()
                        if ((value.startsWith('"') && value.endsWith('"')) ||
                            (value.startsWith('\'') && value.endsWith('\''))) {
                            value = value.substring(1, value.length - 1)
                        }
                        return value
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }
        return null
    }

    private fun buildDocHtml(varName: String, defaultValue: String?, resolution: EnvVarResolution?): String {
        val sb = StringBuilder()

        // Title
        sb.append(DocumentationMarkup.DEFINITION_START)
        sb.append("<b>\${$varName}</b>")
        sb.append(DocumentationMarkup.DEFINITION_END)

        // Content
        sb.append(DocumentationMarkup.CONTENT_START)

        if (resolution != null) {
            sb.append("<p><b>Current value:</b> <code>${escapeHtml(resolution.value ?: "")}</code></p>")
            sb.append("<p><b>Source:</b> ${escapeHtml(resolution.source)}</p>")
        } else {
            sb.append("<p><b>Current value:</b> <i>not set</i></p>")
        }

        if (defaultValue != null) {
            sb.append("<p><b>Default value:</b> <code>${escapeHtml(defaultValue)}</code></p>")
        }

        sb.append("<p><b>Syntax:</b> <code>\${$varName${if (defaultValue != null) ":$defaultValue" else ""}}</code></p>")

        sb.append(DocumentationMarkup.CONTENT_END)

        // Sections
        sb.append(DocumentationMarkup.SECTIONS_START)
        sb.append(DocumentationMarkup.SECTION_HEADER_START)
        sb.append("Runtime")
        sb.append(DocumentationMarkup.SECTION_SEPARATOR)
        val effectiveValue = resolution?.value ?: defaultValue ?: "<i>undefined</i>"
        sb.append("Effective value: <code>${escapeHtml(effectiveValue)}</code>")
        sb.append(DocumentationMarkup.SECTION_END)
        sb.append(DocumentationMarkup.SECTIONS_END)

        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
