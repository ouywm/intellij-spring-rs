package com.springrs.plugin.routes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.SpringRsConstants
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlFileType
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.TomlValue

/**
 * Reads route prefix configuration from spring-rs config files.
 *
 * Supported config keys:
 * - `global_prefix = "/api"` - global route prefix applied to all routes
 * - `doc_prefix = "/docs"` under `[web.openapi]` - OpenAPI doc prefix (currently not applied to route display)
 *
 * Config file priority (highest first):
 * 1. app.toml (default)
 * 2. app-dev.toml, app-test.toml, app-prod.toml (env configs)
 * 3. other app-{env}.toml
 */
object SpringRsConfigPrefixUtil {

    /**
     * Route prefix values.
     */
    data class ConfigPrefixes(
        /** Global route prefix. */
        val globalPrefix: String?,
        /** OpenAPI documentation prefix. */
        val docPrefix: String?
    ) {
        companion object {
            val EMPTY = ConfigPrefixes(null, null)
        }
    }

    /**
     * Reads route prefixes for a given crate.
     *
     * @param project project
     * @param crateRootPath crate root path
     * @return route prefixes; returns [ConfigPrefixes.EMPTY] if not configured
     */
    fun getConfigPrefixes(project: Project, crateRootPath: String): ConfigPrefixes {
        val configFiles = findConfigFilesForCrate(project, crateRootPath)
        if (configFiles.isEmpty()) return ConfigPrefixes.EMPTY

        var globalPrefix: String? = null
        var docPrefix: String? = null

        // Read configs in priority order (first found value wins).
        for (file in configFiles) {
            val (gp, dp) = parseConfigFile(project, file)
            if (globalPrefix == null && gp != null) {
                globalPrefix = gp
            }
            if (docPrefix == null && dp != null) {
                docPrefix = dp
            }
            // Stop once both values are found.
            if (globalPrefix != null && docPrefix != null) break
        }

        return ConfigPrefixes(globalPrefix, docPrefix)
    }

    /**
     * Finds config files under a given crate.
     */
    private fun findConfigFilesForCrate(project: Project, crateRootPath: String): List<VirtualFile> {
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val normalizedRoot = crateRootPath.trimEnd('/')

        return FileTypeIndex.getFiles(TomlFileType, scope)
            .asSequence()
            .filter { vf ->
                // Filename matches app.toml or app-{env}.toml
                SpringRsConfigFileUtil.isConfigFileName(vf.name) &&
                    // File is under the crate root (direct child or nested).
                    vf.path.startsWith("$normalizedRoot/")
            }
            .sortedWith(configFilePriorityComparator())
            .toList()
    }

    /**
     * Config file priority comparator.
     *
     * app.toml > app-dev.toml > app-test.toml > app-prod.toml > others
     */
    private fun configFilePriorityComparator(): Comparator<VirtualFile> {
        return compareBy { vf ->
            val idx = SpringRsConstants.CONFIG_FILE_PRIORITY.indexOfFirst { it.equals(vf.name, ignoreCase = true) }
            if (idx >= 0) idx else Int.MAX_VALUE
        }
    }

    /**
     * Parses a config file and extracts `global_prefix` and `doc_prefix`.
     */
    private fun parseConfigFile(project: Project, file: VirtualFile): Pair<String?, String?> {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? TomlFile ?: run {
            return null to null
        }

        var globalPrefix: String? = null
        var docPrefix: String? = null

        // Get all tables.
        val tables = PsiTreeUtil.findChildrenOfType(psiFile, TomlTable::class.java)

        // 1. Find global_prefix under [web].
        val webTable = tables.firstOrNull { it.header.key?.text == SpringRsConstants.CONFIG_SECTION_WEB }
        if (webTable != null) {
            globalPrefix = findKeyInTable(webTable, SpringRsConstants.CONFIG_KEY_GLOBAL_PREFIX)
        }

        // 2. Find doc_prefix under [web.openapi] or [web].
        // 2.1 Prefer [web.openapi] table.
        val webOpenapiTable = tables.firstOrNull { it.header.key?.text == SpringRsConstants.CONFIG_SECTION_WEB_OPENAPI }
        if (webOpenapiTable != null) {
            docPrefix = findKeyInTable(webOpenapiTable, SpringRsConstants.CONFIG_KEY_DOC_PREFIX)
        }

        // 2.2 If no [web.openapi], try `openapi = { doc_prefix = "/docs" }` inline table under [web].
        if (docPrefix == null && webTable != null) {
            val openapiEntry = webTable.entries.firstOrNull { it.key.text == SpringRsConstants.CONFIG_KEY_OPENAPI }
            if (openapiEntry != null) {
                val inlineTable = openapiEntry.value as? TomlInlineTable
                if (inlineTable != null) {
                    docPrefix = findKeyInInlineTable(inlineTable, SpringRsConstants.CONFIG_KEY_DOC_PREFIX)
                }
            }
        }

        return globalPrefix to docPrefix
    }

    /**
     * Finds a string value for a given key in a TOML table.
     */
    private fun findKeyInTable(table: TomlTable, keyName: String): String? {
        for (entry in table.entries) {
            val kv = entry as? TomlKeyValue ?: continue
            if (kv.key.text == keyName) {
                return extractStringValue(kv.value)
            }
        }
        return null
    }

    /**
     * Finds a string value for a given key in a TOML inline table.
     */
    private fun findKeyInInlineTable(inlineTable: TomlInlineTable, keyName: String): String? {
        for (entry in inlineTable.entries) {
            val kv = entry as? TomlKeyValue ?: continue
            if (kv.key.text == keyName) {
                return extractStringValue(kv.value)
            }
        }
        return null
    }

    /**
     * Extracts string value from a TOML value.
     */
    private fun extractStringValue(value: TomlValue?): String? {
        val literal = value as? TomlLiteral ?: return null
        val text = literal.text
        // Strip quotes.
        return when {
            text.startsWith("\"") && text.endsWith("\"") -> text.substring(1, text.length - 1)
            text.startsWith("'") && text.endsWith("'") -> text.substring(1, text.length - 1)
            else -> text
        }
    }
}
