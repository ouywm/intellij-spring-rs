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

        // Apply per-table overrides (exclude columns, type overrides)
        val effectiveTableInfos = tableInfos.map { table ->
            val override = settings.tableOverrides[table.name]
            table.applyOverride(override)
        }

        // Detect foreign key relations between selected tables
        val relationsMap = RelationDetector.detectRelations(dbTables)

        // Check if user generated from Preview (with possibly edited content)
        val previewFiles = dialog.previewEditedFiles

        ApplicationManager.getApplication().runWriteAction {
            try {
                val allFiles: List<File>
                val generatedOutputDirs: List<String>

                if (previewFiles != null) {
                    // ── Write preview-edited files directly ──
                    val result = writePreviewFiles(basePath, previewFiles)
                    allFiles = result.first
                    generatedOutputDirs = result.second
                } else {
                    // ── Normal generation via templates ──
                    val layers = buildLayerSpecs(dialog, project, relationsMap)
                    val mutableFiles = mutableListOf<File>()
                    val mutableDirs = mutableListOf<String>()
                    for (layer in layers) {
                        if (!layer.enabled) continue
                        val (files, _) = generateLayer(basePath, layer, effectiveTableInfos, project)
                        mutableFiles.addAll(files)
                        mutableDirs.add(layer.outputDir)
                    }
                    allFiles = mutableFiles
                    generatedOutputDirs = mutableDirs
                }

                // Update crate root (main.rs / lib.rs) with mod declarations
                if (generatedOutputDirs.isNotEmpty()) {
                    updateCrateRoot(basePath, generatedOutputDirs)
                }

                // Format generated files with rustfmt
                val formattedCount = RustFormatter.formatFiles(allFiles)

                // Refresh VFS
                val refreshRoot = File(basePath, "src")
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(refreshRoot)?.let {
                    VfsUtil.markDirtyAndRefresh(false, true, true, it)
                }

                val fmtSuffix = if (formattedCount > 0) " (${formattedCount} formatted)" else ""
                notify(project,
                    SpringRsBundle.message("codegen.notify.success.full",
                        tableInfos.size, generatedOutputDirs.size, allFiles.size) + fmtSuffix,
                    NotificationType.INFORMATION)

                allFiles.firstOrNull()?.let { openFile(project, it) }
            } catch (ex: Exception) {
                LOG.error("Code generation failed", ex)
                notify(project,
                    SpringRsBundle.message("codegen.notify.error", ex.message ?: "Unknown error"),
                    NotificationType.ERROR)
            }
        }
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
            // Extract output dir: "src/entity/users.rs" → "src/entity"
            val parentRelative = gf.relativePath.substringBeforeLast("/", "")
            if (parentRelative.isNotEmpty()) outputDirs.add(parentRelative)
        }

        return writtenFiles to outputDirs.toList()
    }

    // ══════════════════════════════════════════════════════════════
    // ── Layer abstraction
    // ══════════════════════════════════════════════════════════════

    private data class LayerSpec(
        val enabled: Boolean,
        val outputDir: String,
        val generate: (TableInfo) -> String,
        val fileName: (TableInfo) -> String,
        val modName: (TableInfo) -> String,
        /** Whether this is the Entity layer (uses marker-based incremental merge). */
        val isEntityLayer: Boolean = false,
        val postProcess: ((File, List<TableInfo>) -> Unit)? = null
    )

    private fun buildLayerSpecs(
        dialog: GenerateSeaOrmDialog, project: Project,
        relationsMap: Map<String, List<RelationInfo>> = emptyMap()
    ): List<LayerSpec> = listOf(
        LayerSpec(
            dialog.isEntityEnabled, dialog.entityOutputDir,
            { SeaOrmEntityGenerator.generate(it, dialog.entityExtraDerives, project, relationsMap[it.name.lowercase()].orEmpty()) },
            SeaOrmEntityGenerator::fileName, SeaOrmEntityGenerator::modName,
            isEntityLayer = true,
            postProcess = { dir, tables ->
                updatePreludeFile(dir, tables)
                updateModFile(dir, listOf("prelude")) // Ensure prelude is declared in mod.rs
            }
        ),
        LayerSpec(dialog.isDtoEnabled, dialog.dtoOutputDir,
            { DtoGenerator.generate(it, dialog.dtoExtraDerives, project) },
            DtoGenerator::fileName, DtoGenerator::modName),
        LayerSpec(dialog.isVoEnabled, dialog.voOutputDir,
            { VoGenerator.generate(it, dialog.voExtraDerives, project) },
            VoGenerator::fileName, VoGenerator::modName),
        LayerSpec(dialog.isServiceEnabled, dialog.serviceOutputDir,
            { ServiceGenerator.generate(it, project) },
            ServiceGenerator::fileName, ServiceGenerator::modName),
        LayerSpec(dialog.isRouteEnabled, dialog.routeOutputDir,
            { RouteGenerator.generate(it, project) },
            RouteGenerator::fileName, RouteGenerator::modName)
    )

    /** Shared conflict resolution when "Apply to all" is checked. */
    private var globalConflictResolution: ConflictResolution? = null

    private fun generateLayer(
        basePath: String, layer: LayerSpec, tables: List<TableInfo>, project: Project
    ): Pair<List<File>, File> {
        val dir = File(basePath, layer.outputDir).also { it.mkdirs() }
        val files = mutableListOf<File>()
        val modules = mutableListOf<String>()

        for (table in tables) {
            val file = File(dir, layer.fileName(table))
            val content = layer.generate(table)

            if (file.exists()) {
                // ── Entity: try incremental merge (marker-based) ──
                if (layer.isEntityLayer && IncrementalMerger.writeWithMerge(file, content, true)) {
                    files.add(file)
                    modules.add(layer.modName(table))
                    continue
                }

                // ── Other layers: file conflict dialog ──
                val resolution = resolveConflict(project, file.path)
                when (resolution) {
                    ConflictResolution.SKIP -> {
                        modules.add(layer.modName(table))
                        continue
                    }
                    ConflictResolution.BACKUP -> {
                        file.copyTo(File(file.path + ".bak"), overwrite = true)
                    }
                    ConflictResolution.OVERWRITE -> { /* fall through to write */ }
                }
            }

            file.writeText(content)
            files.add(file)
            modules.add(layer.modName(table))
        }

        updateModFile(dir, modules)
        layer.postProcess?.invoke(dir, tables)
        return files to dir
    }

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
                    updateModFile(currentDir, listOf(segments[i + 1]))
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

    // ══════════════════════════════════════════════════════════════
    // ── mod.rs / prelude.rs helpers
    // ══════════════════════════════════════════════════════════════

    private fun updateModFile(dir: File, newModules: List<String>) {
        val modFile = File(dir, "mod.rs")
        val existingContent = if (modFile.exists()) modFile.readText() else ""
        val existingModules = MOD_REGEX.findAll(existingContent)
            .map { it.groupValues[1] }
            .toMutableSet()
        existingModules.addAll(newModules)
        modFile.writeText(existingModules.sorted().joinToString("\n") { "pub mod $it;" } + "\n")
    }

    private fun updatePreludeFile(dir: File, tables: List<TableInfo>) {
        val preludeFile = File(dir, "prelude.rs")
        val existing = if (preludeFile.exists()) {
            PRELUDE_REGEX.findAll(preludeFile.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
                .toMutableMap()
        } else mutableMapOf()

        for (table in tables) {
            existing[table.moduleName] = table.entityName
        }

        preludeFile.writeText(
            existing.entries.sortedBy { it.key }
                .joinToString("\n") { "pub use super::${it.key}::Entity as ${it.value};" } + "\n"
        )
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

        /** Matches `pub mod xxx;` in mod.rs files */
        private val MOD_REGEX = Regex("""pub\s+mod\s+(\w+)\s*;""")

        /** Matches `pub use super::xxx::Entity as Xxx;` in prelude.rs */
        private val PRELUDE_REGEX = Regex("""pub\s+use\s+super::(\w+)::Entity\s+as\s+(\w+)\s*;""")

        /** Matches both `mod xxx;` and `pub mod xxx;` in crate root files */
        private val CRATE_MOD_REGEX = Regex("""(?:pub\s+)?mod\s+(\w+)\s*;""")

        /** Matches `use` statements */
        private val USE_REGEX = Regex("""(?:pub\s+)?use\s+""")
    }
}
