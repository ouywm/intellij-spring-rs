package com.springrs.plugin.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.CargoUtils
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind
import java.io.File

/**
 * Annotator that validates `${VAR}` environment variable references in TOML config files.
 *
 * Reports a warning when a referenced env var is not found in:
 * 1. `.env` files (crate root + workspace root)
 * 2. System environment variables
 *
 * Does NOT warn if the reference has a default value (`${VAR:default}`),
 * since the default will be used at runtime.
 */
class SpringRsEnvVarAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (DumbService.isDumb(element.project)) return
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(element)) return

        val literal = element as? TomlLiteral ?: return
        if (literal.kind !is TomlLiteralKind.String) return

        val text = literal.text
        val project = element.project

        // Resolve current crate root for scoped .env reading.
        val filePath = element.containingFile?.virtualFile?.path
        val crateRoot = if (filePath != null) CargoUtils.findCrateRootForFile(project, filePath) else null

        // Load known env vars (cached per annotation pass via lazy).
        val knownVars by lazy { collectKnownEnvVars(project, crateRoot) }

        // Also check for malformed ${...} patterns (empty name or invalid chars).
        val malformedPattern = Regex("""\$\{([^}]*)\}""")
        malformedPattern.findAll(text).forEach { match ->
            val content = match.groupValues[1]
            val contentBeforeColon = content.split(":").first().trim()
            val matchStart = literal.textRange.startOffset + match.range.first
            val matchEnd = literal.textRange.startOffset + match.range.last + 1

            if (matchStart < literal.textRange.startOffset || matchEnd > literal.textRange.endOffset) return@forEach

            // Check empty env var name: ${}
            if (contentBeforeColon.isEmpty()) {
                holder.newAnnotation(
                    HighlightSeverity.ERROR,
                    SpringRsBundle.message("springrs.env.empty.name")
                ).range(TextRange(matchStart, matchEnd)).create()
                return@forEach
            }

            // Check invalid env var name: ${abc-def} (should be uppercase + underscore)
            if (!contentBeforeColon.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                holder.newAnnotation(
                    HighlightSeverity.WARNING,
                    SpringRsBundle.message("springrs.env.invalid.name", contentBeforeColon)
                ).range(TextRange(matchStart, matchEnd)).create()
                return@forEach
            }
        }

        // Check each ${VAR} reference for unresolved variables.
        ENV_VAR_PATTERN.findAll(text).forEach { match ->
            val varName = match.groupValues[1]
            val hasDefault = match.groupValues[2].isNotEmpty() || match.value.contains(':')

            // Skip if there's a default value.
            if (hasDefault) return@forEach

            if (varName !in knownVars) {
                val nameStart = match.range.first + 2
                val nameEnd = nameStart + varName.length
                val absoluteStart = literal.textRange.startOffset + nameStart
                val absoluteEnd = literal.textRange.startOffset + nameEnd

                if (absoluteStart >= literal.textRange.startOffset && absoluteEnd <= literal.textRange.endOffset) {
                    holder.newAnnotation(
                        HighlightSeverity.WARNING,
                        SpringRsBundle.message("springrs.env.unresolved", varName)
                    ).range(TextRange(absoluteStart, absoluteEnd)).create()
                }
            }
        }
    }

    /**
     * Collects all known env var names from .env files and system environment.
     */
    private fun collectKnownEnvVars(project: Project, crateRoot: String?): Set<String> {
        val vars = mutableSetOf<String>()

        // .env files.
        val projectBase = project.basePath
        if (projectBase != null) {
            parseEnvFileKeys(File(projectBase, ".env"), vars)
        }
        if (crateRoot != null && crateRoot != projectBase) {
            parseEnvFileKeys(File(crateRoot, ".env"), vars)
        }

        // System environment.
        try {
            vars.addAll(System.getenv().keys)
        } catch (_: SecurityException) {
            // Ignore.
        }

        return vars
    }

    private fun parseEnvFileKeys(file: File, out: MutableSet<String>) {
        if (!file.exists() || !file.isFile) return
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex > 0) {
                    val key = trimmed.substring(0, eqIndex).trim()
                    if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        out.add(key)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore.
        }
    }
}

private val ENV_VAR_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}""")
