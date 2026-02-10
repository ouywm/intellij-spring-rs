package com.springrs.plugin.codegen

import com.intellij.database.psi.DbTable
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.codegen.dialect.DatabaseDialect
import com.springrs.plugin.codegen.layer.DtoLayer
import com.springrs.plugin.codegen.layer.EntityLayer
import com.springrs.plugin.codegen.layer.RouteLayer
import com.springrs.plugin.codegen.layer.ServiceLayer
import com.springrs.plugin.codegen.layer.VoLayer
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_VALIDATE
import java.io.File

/**
 * Action to generate Sea-ORM code from database tables.
 *
 * Generates up to 5 layers: Entity, DTO, VO, Service, Route.
 * Triggered from the Database view popup menu (right-click on tables).
 */
class GenerateSeaOrmAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val psiElements = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY)
        e.presentation.isEnabledAndVisible =
            psiElements?.any { it is DbTable } == true && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dbTables = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY)
            ?.filterIsInstance<DbTable>()
            ?.takeIf { it.isNotEmpty() } ?: return

        // Reset per-invocation state
        globalConflictResolution = null

        // Auto-detect database dialect + read settings
        val dialect = DatabaseDialect.detect(dbTables.first())
        val settings = CodeGenSettingsState.getInstance(project)
        val tableInfos = dbTables.map {
            TableMetadataReader.readTable(it, dialect, settings.tableNamePrefix, settings.columnNamePrefix)
        }

        // Show configuration dialog
        val dialog = GenerateSeaOrmDialog(project, tableInfos.map { it.name }, tableInfos)
        if (!dialog.showAndGet()) return
        dialog.saveSettings()

        val basePath = project.basePath ?: return

        // Re-read tables with updated prefix (user may have changed it in the dialog)
        val updatedTableInfos = dbTables.map {
            TableMetadataReader.readTable(it, dialect, settings.tableNamePrefix, settings.columnNamePrefix)
        }

        // Apply per-table overrides (exclude columns, type overrides)
        val effectiveTableInfos = updatedTableInfos.map { table ->
            val override = settings.tableOverrides[table.name]
            table.applyOverride(override)
        }

        // Detect foreign key relations between selected tables
        val detectedRelations = RelationDetector.detectRelations(dbTables)

        // Collect user-defined custom relations (FK definitions → BELONGS_TO)
        // and auto-generate reverse relations (HAS_MANY)
        val customRelationsMap = mutableMapOf<String, MutableList<RelationInfo>>()
        val selectedTableNames = effectiveTableInfos.map { it.name.lowercase() }.toSet()
        for (table in effectiveTableInfos) {
            val override = settings.tableOverrides[table.name]
            val customRels = override?.customRelations?.map { it.toRelationInfo() } ?: emptyList()
            if (customRels.isNotEmpty()) {
                customRelationsMap.getOrPut(table.name.lowercase()) { mutableListOf() }.addAll(customRels)
                // Auto-generate reverse relations for BELONGS_TO FKs
                for (rel in customRels) {
                    if (rel.relationType == RelationType.BELONGS_TO && rel.targetTable.lowercase() in selectedTableNames) {
                        customRelationsMap.getOrPut(rel.targetTable.lowercase()) { mutableListOf() }
                            .add(RelationInfo(RelationType.HAS_MANY, table.name, rel.toColumn, rel.fromColumn))
                    }
                }
            }
        }

        // Merge: custom + auto-detected (custom wins on dedup)
        val relationsMap = RelationDetector.mergeRelations(detectedRelations, customRelationsMap)

        // Check if user generated from Preview (with possibly edited content)
        val previewFiles = dialog.previewEditedFiles

        var firstGeneratedFile: File? = null

        // Prepare data outside write action
        val layerConfigs: List<LayerConfig>?
        val generatedOutputDirs: List<String>
        val plan: List<GeneratedFile>?

        if (previewFiles != null) {
            layerConfigs = null
            generatedOutputDirs = emptyList()
            plan = null
        } else {
            layerConfigs = buildLayerConfigs(dialog, project, relationsMap)
            generatedOutputDirs = layerConfigs.filter { it.enabled }.map { it.outputDir }
            plan = CodegenPlan.plan(layerConfigs, effectiveTableInfos, project)
        }

        // Phase 1: Detect conflicts and resolve via dialog (OUTSIDE write action)
        val resolutions = mutableMapOf<String, ConflictResolution>()
        if (plan != null) {
            val conflicts = CodegenExecutor.detectConflicts(basePath, plan)
            for (path in conflicts) {
                val resolution = resolveConflict(project, path)
                resolutions[path] = resolution
            }
        }

        // Phase 2: Write files (INSIDE write action)
        ApplicationManager.getApplication().runWriteAction {
            try {
                val allFiles: List<File>

                if (previewFiles != null) {
                    val result = writePreviewFiles(basePath, previewFiles)
                    allFiles = result.first
                    val previewOutputDirs = result.second
                    if (previewOutputDirs.isNotEmpty()) {
                        updateCrateRoot(basePath, previewOutputDirs)
                    }
                } else {
                    allFiles = CodegenExecutor.writeFiles(basePath, plan!!, resolutions)
                    if (generatedOutputDirs.isNotEmpty()) {
                        updateCrateRoot(basePath, generatedOutputDirs)
                    }
                }

                val formattedCount = RustFormatter.formatFiles(allFiles)

                val refreshRoot = File(basePath, "src")
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(refreshRoot)?.let {
                    VfsUtil.markDirtyAndRefresh(false, true, true, it)
                }

                val allDerives = mutableSetOf<String>()
                allDerives.addAll(dialog.entityExtraDerives)
                allDerives.addAll(dialog.dtoExtraDerives)
                allDerives.addAll(dialog.voExtraDerives)
                val routeWithValidate = dialog.isRouteEnabled && DERIVE_VALIDATE in dialog.dtoExtraDerives
                val addedCrates = CargoDependencyManager.ensureDependencies(project, allDerives, effectiveTableInfos, routeWithValidate)

                val fmtSuffix = if (formattedCount > 0) " (${formattedCount} formatted)" else ""
                val depSuffix = if (addedCrates.isNotEmpty()) "\nAdded to Cargo.toml: ${addedCrates.joinToString(", ")}" else ""
                notify(project,
                    SpringRsBundle.message("codegen.notify.success.full",
                        tableInfos.size, generatedOutputDirs.size, allFiles.size) + fmtSuffix + depSuffix,
                    NotificationType.INFORMATION)

                firstGeneratedFile = allFiles.firstOrNull()
            } catch (ex: Exception) {
                LOG.error("Code generation failed", ex)
                notify(project,
                    SpringRsBundle.message("codegen.notify.error", ex.message ?: "Unknown error"),
                    NotificationType.ERROR)
            }
        }

        // Open file OUTSIDE runWriteAction (needs read access, not write)
        firstGeneratedFile?.let { openFile(project, it) }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Layer config construction
    // ══════════════════════════════════════════════════════════════

    private fun buildLayerConfigs(
        dialog: GenerateSeaOrmDialog, project: Project,
        relationsMap: Map<String, List<RelationInfo>> = emptyMap()
    ): List<LayerConfig> {
        val settings = CodeGenSettingsState.getInstance(project)

        // Per-table layer dependency propagation (mirrors dialog-level logic):
        // Route → Service + DTO; Service → DTO + VO + Entity; DTO/VO → Entity
        fun isRouteEnabledFor(table: String): Boolean =
            settings.tableOverrides[table]?.generateRoute ?: true

        fun isServiceEnabledFor(table: String): Boolean =
            (settings.tableOverrides[table]?.generateService ?: true) || isRouteEnabledFor(table)

        fun isDtoEnabledFor(table: String): Boolean =
            (settings.tableOverrides[table]?.generateDto ?: true) || isServiceEnabledFor(table) || isRouteEnabledFor(table)

        fun isVoEnabledFor(table: String): Boolean =
            (settings.tableOverrides[table]?.generateVo ?: true) || isServiceEnabledFor(table)

        fun isEntityEnabledFor(table: String): Boolean =
            (settings.tableOverrides[table]?.generateEntity ?: true) || isDtoEnabledFor(table) || isVoEnabledFor(table) || isServiceEnabledFor(table)

        return listOf(
            LayerConfig(
                EntityLayer(dialog.entityExtraDerives, relationsMap, dialog.entityDerivesOverrides),
                dialog.isEntityEnabled, dialog.entityOutputDir,
                isTableEnabled = ::isEntityEnabledFor,
                outputDirForTable = dialog.entityOutputDirForTable
            ),
            LayerConfig(
                DtoLayer(dialog.dtoExtraDerives, dialog.dtoDerivesOverrides),
                dialog.isDtoEnabled, dialog.dtoOutputDir,
                isTableEnabled = ::isDtoEnabledFor,
                outputDirForTable = dialog.dtoOutputDirForTable
            ),
            LayerConfig(
                VoLayer(dialog.voExtraDerives, dialog.voDerivesOverrides),
                dialog.isVoEnabled, dialog.voOutputDir,
                isTableEnabled = ::isVoEnabledFor,
                outputDirForTable = dialog.voOutputDirForTable
            ),
            LayerConfig(
                ServiceLayer(), dialog.isServiceEnabled, dialog.serviceOutputDir,
                isTableEnabled = ::isServiceEnabledFor,
                outputDirForTable = dialog.serviceOutputDirForTable
            ),
            LayerConfig(
                RouteLayer(dialog.dtoExtraDerives), dialog.isRouteEnabled, dialog.routeOutputDir,
                isTableEnabled = ::isRouteEnabledFor,
                outputDirForTable = dialog.routeOutputDirForTable
            )
        )
    }

    // ══════════════════════════════════════════════════════════════
    // ── Write preview-edited files
    // ══════════════════════════════════════════════════════════════

    /**
     * Write files from [CodePreviewDialog] (user may have edited content).
     * Returns (written files, unique output directories).
     */
    private fun writePreviewFiles(
        basePath: String, previewFiles: List<GeneratedFile>
    ): Pair<List<File>, List<String>> {
        val writtenFiles = mutableListOf<File>()
        val outputDirs = mutableSetOf<String>()

        for (gf in previewFiles) {
            val file = File(basePath, gf.relativePath)
            file.parentFile?.mkdirs()
            file.writeText(gf.content)
            writtenFiles.add(file)
            val parentRelative = gf.relativePath.substringBeforeLast("/", "")
            if (parentRelative.isNotEmpty()) {
                outputDirs.add(parentRelative)
            }
        }

        // mod.rs and prelude.rs are already included in previewFiles
        // (generated by CodegenPlan.plan), so no extra merge needed.

        return writtenFiles to outputDirs.toList()
    }

    // ══════════════════════════════════════════════════════════════
    // ── Conflict resolution
    // ══════════════════════════════════════════════════════════════

    /** Shared conflict resolution when "Apply to all" is checked. */
    private var globalConflictResolution: ConflictResolution? = null

    /**
     * Resolve file conflict: use global resolution if set, otherwise show dialog.
     */
    private fun resolveConflict(project: Project, filePath: String): ConflictResolution {
        globalConflictResolution?.let { return it }

        val dialog = FileConflictDialog(project, filePath)
        dialog.show()
        val resolution = dialog.resolution
        if (dialog.applyToAllCheckBox.isSelected) {
            globalConflictResolution = resolution
        }
        return resolution
    }

    // ══════════════════════════════════════════════════════════════
    // ── Crate root (main.rs / lib.rs) module declarations
    // ══════════════════════════════════════════════════════════════

    /**
     * Ensure generated layer modules are declared in the crate root (`main.rs` or `lib.rs`).
     *
     * For `src/entity` → adds `mod entity;` to main.rs
     * For `src/models/entity` → adds `mod models;` to main.rs
     *   AND `pub mod entity;` to `src/models/mod.rs`
     */
    private fun updateCrateRoot(basePath: String, outputDirs: List<String>) {
        val srcDir = File(basePath, "src")
        if (!srcDir.exists()) return

        val rootFile = findCrateRootFile(srcDir) ?: return

        val topLevelModules = mutableSetOf<String>()

        for (outputDir in outputDirs) {
            // "src/entity" → ["entity"],  "src/models/entity" → ["models", "entity"]
            val segments = outputDir.removePrefix("src/").removePrefix("src\\")
                .split("/", "\\").filter { it.isNotBlank() }
            if (segments.isEmpty()) continue

            // Top-level module goes into main.rs / lib.rs
            topLevelModules.add(segments.first())

            // Nested: ensure intermediate mod.rs files declare their children
            //   e.g. src/models/ needs mod.rs with `pub mod entity;`
            if (segments.size > 1) {
                var currentDir = srcDir
                for (i in 0 until segments.size - 1) {
                    currentDir = File(currentDir, segments[i]).also { it.mkdirs() }
                    updateIntermediateModFile(currentDir, listOf(segments[i + 1]))
                }
            }
        }

        if (topLevelModules.isNotEmpty()) {
            smartMergeModDeclarations(rootFile, topLevelModules)
        }
    }

    /**
     * Find crate root file: prefer `lib.rs`, fallback to `main.rs`.
     */
    private fun findCrateRootFile(srcDir: File): File? {
        return File(srcDir, "lib.rs").takeIf { it.exists() }
            ?: File(srcDir, "main.rs").takeIf { it.exists() }
    }

    /**
     * Update intermediate mod.rs for crate root path resolution.
     * Uses [ModuleFileGenerator] for consistent mod.rs handling.
     */
    private fun updateIntermediateModFile(dir: File, newModules: List<String>) {
        val modFile = File(dir, "mod.rs")
        if (modFile.exists()) {
            modFile.writeText(ModuleFileGenerator.mergeModContent(modFile.readText(), newModules))
        } else {
            modFile.writeText(ModuleFileGenerator.generateModContent(newModules))
        }
    }

    /**
     * Smart-merge `mod xxx;` declarations into a Rust source file.
     *
     * Strategy:
     * 1. Parse existing `mod` declarations in the file
     * 2. Determine which are missing
     * 3. Insert missing ones after the last existing `mod` block,
     *    or before the first `use` / `fn` / `struct` if no `mod` exists,
     *    or at the top after inner attributes (`#![...]`) and doc comments (`//!`).
     */
    private fun smartMergeModDeclarations(file: File, newModules: Set<String>) {
        val content = file.readText()

        // Parse existing mod declarations (both `mod x;` and `pub mod x;`)
        val existingMods = CRATE_MOD_REGEX.findAll(content)
            .map { it.groupValues[1] }
            .toSet()

        val missing = newModules.filter { it !in existingMods }.sorted()
        if (missing.isEmpty()) return

        val modLines = missing.joinToString("\n") { "mod $it;" }

        // Find the best insertion point
        val insertOffset = findModInsertionOffset(content)

        val newContent = buildString {
            append(content, 0, insertOffset)
            // Ensure blank line separation
            if (insertOffset > 0 && content[insertOffset - 1] != '\n') append('\n')
            append(modLines)
            append('\n')
            // Separate from following code
            if (insertOffset < content.length && content[insertOffset] != '\n') append('\n')
            append(content, insertOffset, content.length)
        }

        file.writeText(newContent)
    }

    /**
     * Find the best offset to insert new `mod` declarations.
     *
     * Priority:
     * 1. After the last existing `mod xxx;` line → group mod declarations together
     * 2. Before the first `use` statement → standard Rust convention
     * 3. After inner attributes and doc comments → top of file
     */
    private fun findModInsertionOffset(content: String): Int {
        // 1. After last existing `mod xxx;`
        val lastMod = CRATE_MOD_REGEX.findAll(content).lastOrNull()
        if (lastMod != null) {
            val afterMod = lastMod.range.last + 1
            // Skip trailing newline
            return if (afterMod < content.length && content[afterMod] == '\n') afterMod + 1 else afterMod
        }

        // 2. Before first `use`
        val firstUse = USE_REGEX.find(content)
        if (firstUse != null) return firstUse.range.first

        // 3. After inner attributes (#![...]) and inner doc comments (//!)
        return skipInnerAttributesAndDocs(content)
    }

    /**
     * Skip past `#![...]` inner attributes and `//!` inner doc comments at the top of the file.
     */
    private fun skipInnerAttributesAndDocs(content: String): Int {
        var i = 0
        val len = content.length
        while (i < len) {
            // Skip whitespace
            while (i < len && content[i].isWhitespace()) i++
            if (i >= len) break

            when {
                // Inner doc comment: //!
                content.startsWith("//!", i) -> {
                    i = content.indexOf('\n', i).let { if (it == -1) len else it + 1 }
                }
                // Inner attribute: #![...]
                content.startsWith("#!", i) && i + 2 < len && content.indexOf('[', i + 1) < content.indexOf('\n', i + 1).let { if (it == -1) len else it } -> {
                    var depth = 0
                    val bracketStart = content.indexOf('[', i)
                    if (bracketStart == -1) break
                    var j = bracketStart
                    while (j < len) {
                        when (content[j]) {
                            '[' -> depth++
                            ']' -> { depth--; if (depth == 0) { i = j + 1; break } }
                        }
                        j++
                    }
                    if (depth != 0) break
                    // Skip newline after attribute
                    if (i < len && content[i] == '\n') i++
                }
                // Regular line comment (not inner doc)
                content.startsWith("//", i) && !content.startsWith("//!", i) -> {
                    i = content.indexOf('\n', i).let { if (it == -1) len else it + 1 }
                }
                else -> break
            }
        }
        return i
    }

    // ── Utilities ──

    private fun openFile(project: Project, file: File) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { vf ->
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        Notifications.Bus.notify(
            Notification("SpringRs.Notifications",
                SpringRsBundle.message("codegen.notification.title"), content, type),
            project
        )
    }

    companion object {
        private val LOG = logger<GenerateSeaOrmAction>()

        /** Matches both `mod xxx;` and `pub mod xxx;` in crate root files */
        private val CRATE_MOD_REGEX = Regex("""(?:pub\s+)?mod\s+(\w+)\s*;""")

        /** Matches `use` statements */
        private val USE_REGEX = Regex("""(?:pub\s+)?use\s+""")
    }
}
