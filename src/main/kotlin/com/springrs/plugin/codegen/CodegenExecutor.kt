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
 *
 * Two-phase approach to avoid showing dialogs inside write actions:
 * 1. [detectConflicts] — scan for conflicts (no write action needed)
 * 2. [writeFiles] — write all files using pre-resolved decisions (inside write action)
 */
object CodegenExecutor {

    /**
     * Phase 1: Detect which planned files conflict with existing files on disk.
     *
     * @return List of absolute file paths that already exist and are not mod.rs/prelude.rs.
     */
    fun detectConflicts(basePath: String, files: List<GeneratedFile>): List<String> {
        return files.mapNotNull { gf ->
            val file = File(basePath, gf.relativePath)
            if (file.exists() && file.name != "mod.rs" && file.name != "prelude.rs") {
                file.path
            } else null
        }
    }

    /**
     * Phase 2: Write all planned files to disk.
     *
     * @param basePath           Project base path (absolute).
     * @param files              Planned files from [CodegenPlan.plan].
     * @param resolutions        Pre-resolved conflict decisions (file path → resolution).
     * @return List of files that were written or modified.
     */
    fun writeFiles(
        basePath: String,
        files: List<GeneratedFile>,
        resolutions: Map<String, ConflictResolution> = emptyMap()
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

                // Existing files: use pre-resolved conflict decision
                file.exists() -> {
                    val resolution = resolutions[file.path] ?: ConflictResolution.SKIP
                    when (resolution) {
                        ConflictResolution.SKIP -> continue
                        ConflictResolution.BACKUP -> {
                            file.copyTo(File(file.path + ".bak"), overwrite = true)
                            file.writeText(gf.content)
                        }
                        ConflictResolution.OVERWRITE -> {
                            file.writeText(gf.content)
                        }
                    }
                }

                // New file: direct write
                else -> file.writeText(gf.content)
            }
            writtenFiles.add(file)
        }
        return writtenFiles
    }
}