package com.springrs.plugin.codegen

import java.io.File

/**
 * Responsible for writing planned files to disk.
 *
 * Handles:
 * - **mod.rs**: Incremental merge (append new modules, never remove existing).
 * - **prelude.rs**: Incremental merge (append new entity re-exports).
 * - **Existing files**: Conflict resolution via user dialog (Skip/Overwrite/Backup).
 * - **New files**: Direct write.
 */
object CodegenExecutor {

    /**
     * Write all planned files to disk.
     *
     * @param basePath           Project base path (absolute).
     * @param files              Planned files from [CodegenPlan.plan].
     * @param layers             Layer configs (for future extensibility).
     * @param conflictResolver   Callback to resolve file conflicts (returns [ConflictResolution]).
     * @return List of files that were written or modified.
     */
    fun writeFiles(
        basePath: String,
        files: List<GeneratedFile>,
        layers: List<LayerConfig>,
        conflictResolver: (filePath: String) -> ConflictResolution
    ): List<File> {
        val writtenFiles = mutableListOf<File>()

        for (gf in files) {
            val file = File(basePath, gf.relativePath)
            file.parentFile?.mkdirs()

            when {
                // mod.rs: always merge (append new modules)
                file.name == "mod.rs" && file.exists() -> {
                    val newModules = ModuleFileGenerator.extractModNames(gf.content)
                    val merged = ModuleFileGenerator.mergeModContent(file.readText(), newModules)
                    file.writeText(merged)
                }

                // prelude.rs: always merge (append new entity re-exports)
                file.name == "prelude.rs" && file.exists() -> {
                    val entries = ModuleFileGenerator.extractPreludeEntries(gf.content)
                    val existing = file.readText()
                    val existingEntries = ModuleFileGenerator.extractPreludeEntries(existing).toMutableMap()
                    existingEntries.putAll(entries)
                    val merged = existingEntries.entries.sortedBy { it.key }
                        .joinToString("\n") { "pub use super::${it.key}::Entity as ${it.value};" } + "\n"
                    file.writeText(merged)
                }

                // Existing files: conflict resolution
                file.exists() -> {
                    handleConflict(file, gf.content, conflictResolver) ?: continue
                }

                // New file: direct write
                else -> file.writeText(gf.content)
            }
            writtenFiles.add(file)
        }
        return writtenFiles
    }

    /**
     * Handle file conflict: resolve via callback, apply resolution.
     *
     * @return The file if it should be counted as written, or `null` if skipped.
     */
    private fun handleConflict(
        file: File,
        newContent: String,
        conflictResolver: (String) -> ConflictResolution
    ): File? {
        return when (conflictResolver(file.path)) {
            ConflictResolution.SKIP -> null
            ConflictResolution.BACKUP -> {
                file.copyTo(File(file.path + ".bak"), overwrite = true)
                file.writeText(newContent)
                file
            }
            ConflictResolution.OVERWRITE -> {
                file.writeText(newContent)
                file
            }
        }
    }
}
