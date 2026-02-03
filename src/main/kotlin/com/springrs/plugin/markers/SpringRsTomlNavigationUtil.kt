package com.springrs.plugin.markers

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.CargoUtils
import com.springrs.plugin.utils.SpringRsConstants
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlFileType
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

object SpringRsTomlNavigationUtil {

    /**
     * Compute navigation descriptor (scoped to current crate if possible).
     *
     * @param project project
     * @param sectionName section name
     * @param keyName key name (optional)
     * @param sourceElement source element (used to resolve current crate)
     */
    fun computeNavigationDescriptor(
        project: Project,
        sectionName: String,
        keyName: String?,
        sourceElement: PsiElement? = null
    ): OpenFileDescriptor? {
        // Resolve current crate root.
        val crateRoot = sourceElement?.let { element ->
            val filePath = element.containingFile?.virtualFile?.path
            if (filePath != null) CargoUtils.findCrateRootForFile(project, filePath) else null
        }

        val configFiles = findSpringRsConfigFiles(project, crateRoot)
        if (configFiles.isEmpty()) return null

        val preferred = pickBestConfigLocation(project, configFiles, sectionName, keyName)
        if (preferred != null) {
            return OpenFileDescriptor(project, preferred.file, preferred.offset)
        }

        // Fallback: open the default config if present, otherwise open the first config file.
        val fallbackFile = configFiles.firstOrNull { it.name.equals(SpringRsConstants.CONFIG_FILE_DEFAULT, ignoreCase = true) } ?: configFiles.first()
        return OpenFileDescriptor(project, fallbackFile, 0)
    }

    fun navigateToConfig(project: Project, sectionName: String, keyName: String?, sourceElement: PsiElement? = null) {
        computeNavigationDescriptor(project, sectionName, keyName, sourceElement)?.navigate(true)
    }

    /**
     * Find spring-rs config files.
     *
     * @param project project
     * @param crateRoot restrict to the given crate root (if null, search the whole project)
     */
    private fun findSpringRsConfigFiles(project: Project, crateRoot: String?): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        return FileTypeIndex.getFiles(TomlFileType, scope)
            .asSequence()
            .filter { SpringRsConfigFileUtil.isConfigFileName(it.name) }
            .filter { vf ->
                // If crate root is set, keep only config files under that crate.
                if (crateRoot != null) {
                    vf.path.startsWith(crateRoot.trimEnd('/') + "/")
                } else {
                    true
                }
            }
            .sortedBy { it.path }
            .toList()
    }

    private data class ConfigMatch(
        val file: com.intellij.openapi.vfs.VirtualFile,
        val offset: Int,
        /** 2 = section+key matched, 1 = section matched */
        val score: Int
    )

    private fun pickBestConfigLocation(
        project: Project,
        files: List<com.intellij.openapi.vfs.VirtualFile>,
        sectionName: String,
        keyName: String?
    ): ConfigMatch? {
        val defaultFile = files.firstOrNull { it.name.equals(SpringRsConstants.CONFIG_FILE_DEFAULT, ignoreCase = true) }

        // If key is specified, prefer an exact key match; otherwise section match is enough.
        val targetScore = if (keyName.isNullOrBlank()) 1 else 2

        val orderedFiles = buildList {
            defaultFile?.let(::add)
            addAll(orderNonDefaultFiles(files.filter { it != defaultFile }))
        }

        var best: ConfigMatch? = null
        for (vf in orderedFiles) {
            val match = findMatchInFile(project, vf, sectionName, keyName) ?: continue
            if (best == null || match.score > best.score) {
                best = match
                if (best.score >= targetScore) break
            }
        }

        return best
    }

    private fun orderNonDefaultFiles(files: List<com.intellij.openapi.vfs.VirtualFile>): List<com.intellij.openapi.vfs.VirtualFile> {
        // For typical spring-rs multi-env config, keep a stable, user-friendly order.
        val priority = SpringRsConstants.CONFIG_FILE_ENV_PRIORITY
        return files.sortedWith(
            compareBy<com.intellij.openapi.vfs.VirtualFile> { vf ->
                val idx = priority.indexOfFirst { it.equals(vf.name, ignoreCase = true) }
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it.name.lowercase() }.thenBy { it.path }
        )
    }

    private fun findMatchInFile(
        project: Project,
        file: com.intellij.openapi.vfs.VirtualFile,
        sectionName: String,
        keyName: String?
    ): ConfigMatch? {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? TomlFile ?: return null
        val tables = PsiTreeUtil.findChildrenOfType(psiFile, TomlTable::class.java)
        val table = tables.firstOrNull { it.header.key?.text == sectionName } ?: return null

        if (keyName.isNullOrBlank()) {
            return ConfigMatch(file, table.header.textOffset, 1)
        }

        val kv = table.entries
            .asSequence()
            .filterIsInstance<TomlKeyValue>()
            .firstOrNull { it.key.text == keyName }

        return if (kv != null) {
            ConfigMatch(file, kv.key.textOffset, 2)
        } else {
            // If no exact key match exists in any file, we still want to land in the right section.
            ConfigMatch(file, table.header.textOffset, 1)
        }
    }
}
