package com.springrs.plugin.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import com.springrs.plugin.utils.CargoUtils
import com.springrs.plugin.utils.SpringRsConstants
import org.toml.lang.psi.TomlFileType
import java.io.File

/**
 * Completion provider for environment variables in TOML config values.
 *
 * Provides completion for the `${VAR:default}` syntax used by spring-rs
 * for environment variable resolution in config files.
 *
 * Sources (priority high to low):
 * 1. `.env` files in workspace / crate roots
 * 2. TOML config files — already used `${VAR}` references
 * 3. Spring-rs known environment variables (from framework docs)
 * 4. System environment variables
 *
 * Case-insensitive matching.
 *
 * @see <a href="https://deepwiki.com/spring-rs/spring-rs">spring-rs docs</a>
 * @see <a href="https://deepwiki.com/spring-rs/spring-lsp">spring-lsp docs</a>
 */
class SpringRsEnvVarCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        /**
         * Known spring-rs environment variables.
         *
         * Based on spring-rs framework and plugin documentation:
         * - Core: SPRING_ENV
         * - Database: DATABASE_URL
         * - Redis: REDIS_URL
         * - Web: HOST, PORT
         * - Logging: LOG_LEVEL, RUST_LOG
         * - OpenTelemetry: OTEL_* variables
         * - Mail: MAIL_* variables
         * - Stream: STREAM_URI
         *
         * @see <a href="https://deepwiki.com/spring-rs/spring-rs#2.1">spring-rs Configuration System</a>
         */
        private val SPRING_RS_ENV_VARS = listOf(
            // Core
            EnvVarInfo("SPRING_ENV", "Application environment (dev, test, prod)", "dev", "spring-rs"),

            // Web (spring-web)
            EnvVarInfo("HOST", "Server bind host", "127.0.0.1", "spring-web"),
            EnvVarInfo("PORT", "Server port number", "8080", "spring-web"),

            // Database (spring-sqlx / spring-sea-orm / spring-postgres)
            EnvVarInfo("DATABASE_URL", "Database connection URL", "postgres://localhost/mydb", "spring-sqlx"),

            // Redis (spring-redis)
            EnvVarInfo("REDIS_URL", "Redis connection URL", "redis://127.0.0.1/", "spring-redis"),

            // Logging
            EnvVarInfo("LOG_LEVEL", "Logging level", "info", "spring-rs"),
            EnvVarInfo("RUST_LOG", "Rust tracing filter", "info", "tracing"),
            EnvVarInfo("ENV", "Runtime environment", "", "spring-rs"),
            EnvVarInfo("DEBUG", "Debug mode", "false", "spring-rs"),

            // Mail (spring-mail)
            EnvVarInfo("MAIL_HOST", "SMTP mail server host", "smtp.gmail.com", "spring-mail"),
            EnvVarInfo("MAIL_PORT", "SMTP mail server port", "587", "spring-mail"),
            EnvVarInfo("MAIL_USERNAME", "SMTP mail username", "", "spring-mail"),
            EnvVarInfo("MAIL_PASSWORD", "SMTP mail password", "", "spring-mail"),

            // Stream (spring-stream)
            EnvVarInfo("STREAM_URI", "Stream connection URI", "", "spring-stream"),

            // OpenTelemetry (spring-opentelemetry)
            EnvVarInfo("OTEL_EXPORTER_OTLP_ENDPOINT", "OTLP exporter endpoint", "http://localhost:4317", "spring-opentelemetry"),
            EnvVarInfo("OTEL_EXPORTER_OTLP_HEADERS", "OTLP exporter headers", "", "spring-opentelemetry"),
            EnvVarInfo("OTEL_EXPORTER_OTLP_TIMEOUT", "OTLP exporter timeout (ms)", "10000", "spring-opentelemetry"),
            EnvVarInfo("OTEL_EXPORTER_OTLP_COMPRESSION", "OTLP exporter compression", "gzip", "spring-opentelemetry"),

            // Common application secrets
            EnvVarInfo("SECRET_KEY", "Application secret key", "", "app"),
            EnvVarInfo("JWT_SECRET", "JWT signing secret", "", "app"),
        )

        /** Regex to extract `${VAR}` or `${VAR:default}` from TOML text. */
        private val ENV_VAR_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?\}""")

        private data class EnvVarInfo(
            val name: String,
            val description: String,
            val defaultValue: String,
            val source: String
        )
    }

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(parameters.position)) return

        val document = parameters.editor.document
        val cursorOffset = parameters.offset
        if (cursorOffset <= 0) return

        val lineNumber = document.getLineNumber(cursorOffset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val textBeforeCursor = document.getText(com.intellij.openapi.util.TextRange(lineStart, cursorOffset))

        val lastDollarBrace = textBeforeCursor.lastIndexOf("\${")
        if (lastDollarBrace < 0) return

        val afterDollarBrace = textBeforeCursor.substring(lastDollarBrace + 2)
        if (afterDollarBrace.contains('}')) return

        val typedPrefix = afterDollarBrace
            .replace(SpringRsConstants.INTELLIJ_COMPLETION_DUMMY, "")
            .trim()

        // Case-insensitive matching.
        val caseInsensitiveResult = result.withPrefixMatcher(PlainPrefixMatcher(typedPrefix))

        val project = parameters.position.project
        val addedNames = mutableSetOf<String>()

        // Resolve the crate root for the current file (for workspace isolation).
        val currentFilePath = parameters.originalFile.virtualFile?.path
        val currentCrateRoot = if (currentFilePath != null) {
            CargoUtils.findCrateRootForFile(project, currentFilePath)
        } else null

        // Insert handler: after inserting the var name, append `}` if not already present.
        val closeBraceHandler = com.intellij.codeInsight.completion.InsertHandler<com.intellij.codeInsight.lookup.LookupElement> { ctx, _ ->
            val doc = ctx.document
            val tailOffset = ctx.tailOffset
            // Check if the character right after the inserted text is already `}`.
            val hasClosingBrace = tailOffset < doc.textLength && doc.charsSequence[tailOffset] == '}'
            if (!hasClosingBrace) {
                doc.insertString(tailOffset, "}")
                ctx.editor.caretModel.moveToOffset(tailOffset + 1)
            }
        }

        // === Source 1: .env files (scoped to current crate) ===
        val dotEnvVars = readDotEnvFiles(project, currentCrateRoot)
        for ((name, value) in dotEnvVars) {
            if (!addedNames.add(name)) continue
            caseInsensitiveResult.addElement(
                LookupElementBuilder.create(name)
                    .withIcon(AllIcons.Nodes.Variable)
                    .withTypeText(".env")
                    .withTailText(if (value.isNotEmpty()) "  = $value" else "", true)
                    .withLookupString(name.lowercase())
                    .withInsertHandler(closeBraceHandler)
            )
        }

        // === Source 2: Already used ${VAR} in TOML files (scoped to current crate) ===
        val usedVars = scanUsedEnvVarsInToml(project, currentCrateRoot)
        for (name in usedVars) {
            if (!addedNames.add(name)) continue
            caseInsensitiveResult.addElement(
                LookupElementBuilder.create(name)
                    .withIcon(AllIcons.Nodes.Variable)
                    .withTypeText("used in config")
                    .withLookupString(name.lowercase())
                    .withInsertHandler(closeBraceHandler)
            )
        }

        // === Source 3: Spring-rs known env vars ===
        for (envVar in SPRING_RS_ENV_VARS) {
            if (!addedNames.add(envVar.name)) continue
            caseInsensitiveResult.addElement(
                LookupElementBuilder.create(envVar.name)
                    .withIcon(AllIcons.Nodes.Variable)
                    .withTypeText(envVar.source)
                    .withTailText(
                        if (envVar.defaultValue.isNotEmpty()) "  (default: ${envVar.defaultValue})" else "",
                        true
                    )
                    .withLookupString(envVar.name.lowercase())
                    .withInsertHandler(closeBraceHandler)
            )
        }

        // === Source 4: System environment ===
        try {
            val systemEnv = System.getenv()
            for ((key, _) in systemEnv) {
                if (!addedNames.add(key)) continue
                caseInsensitiveResult.addElement(
                    LookupElementBuilder.create(key)
                        .withIcon(AllIcons.Nodes.Variable)
                        .withTypeText("system env")
                        .withLookupString(key.lowercase())
                        .withInsertHandler(closeBraceHandler)
                )
            }
        } catch (_: SecurityException) {
            // Ignore.
        }
    }

    // ==================== .env File Reading ====================

    /**
     * Reads `.env` files scoped to the current crate.
     *
     * Priority:
     * 1. Current crate root `.env` (most specific)
     * 2. Workspace/project root `.env` (shared across crates)
     *
     * In a single-crate project, both are the same directory.
     *
     * @param project current project
     * @param currentCrateRoot crate root path for the current file (null if unknown)
     * @return map of VAR_NAME to VALUE
     */
    private fun readDotEnvFiles(project: Project, currentCrateRoot: String?): Map<String, String> {
        val result = linkedMapOf<String, String>()

        // Workspace/project root .env (shared config, loaded first so crate-specific can override).
        val projectBase = project.basePath
        if (projectBase != null) {
            parseDotEnvFile(File(projectBase, ".env"), result)
        }

        // Current crate root .env (overrides workspace root if different).
        if (currentCrateRoot != null && currentCrateRoot != projectBase) {
            parseDotEnvFile(File(currentCrateRoot, ".env"), result)
        }

        return result
    }

    /**
     * Parses a single `.env` file.
     *
     * Supports:
     * - KEY=VALUE
     * - KEY="VALUE"
     * - KEY='VALUE'
     * - # comments
     * - empty lines
     */
    private fun parseDotEnvFile(file: File, out: MutableMap<String, String>) {
        if (!file.exists() || !file.isFile) return

        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                // Skip comments and empty lines.
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach

                val eqIndex = trimmed.indexOf('=')
                if (eqIndex <= 0) return@forEach

                val key = trimmed.substring(0, eqIndex).trim()
                var value = trimmed.substring(eqIndex + 1).trim()

                // Strip surrounding quotes.
                if ((value.startsWith('"') && value.endsWith('"')) ||
                    (value.startsWith('\'') && value.endsWith('\''))) {
                    value = value.substring(1, value.length - 1)
                }

                // Only add valid env var names.
                if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    out[key] = value
                }
            }
        } catch (_: Exception) {
            // Ignore read errors.
        }
    }

    // ==================== TOML ${VAR} Scanning ====================

    /**
     * Scans TOML config files for already used `${VAR}` patterns.
     *
     * Scoped to the current crate: only scans `app*.toml` files under the crate root.
     *
     * @param project current project
     * @param currentCrateRoot crate root path (null = scan all)
     * @return set of variable names
     */
    private fun scanUsedEnvVarsInToml(project: Project, currentCrateRoot: String?): Set<String> {
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return emptySet()
        val vars = mutableSetOf<String>()
        val scope = GlobalSearchScope.projectScope(project)

        try {
            for (vFile in FileTypeIndex.getFiles(TomlFileType, scope)) {
                if (!SpringRsConfigFileUtil.isConfigFileName(vFile.name)) continue

                // Scope to current crate: skip TOML files outside the crate root.
                if (currentCrateRoot != null && !vFile.path.startsWith(currentCrateRoot.trimEnd('/') + "/")) {
                    continue
                }

                val virtualFile = VfsUtil.findFileByIoFile(File(vFile.path), false) ?: continue
                val text = try {
                    String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
                } catch (_: Exception) {
                    continue
                }

                ENV_VAR_PATTERN.findAll(text).forEach { match ->
                    vars.add(match.groupValues[1])
                }
            }
        } catch (_: Exception) {
            // Ignore indexing errors.
        }

        return vars
    }
}
